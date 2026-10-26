"""Opt-in synthetic Chinese JPEG/QR CPU acceptance; never reads real receipts."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import time

from finance_ocr.models import verified_models
from finance_ocr.server import Server, Worker

TEST_TOKEN = "synthetic-test-token-not-a-real-secret-0001"


def memory_mib(pid):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in
                ('PeakWorkingSetSize', 'WorkingSetSize', 'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage',
                 'QuotaPeakNonPagedPoolUsage', 'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage')]
        ctypes.windll.kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        ctypes.windll.kernel32.OpenProcess.restype = wintypes.HANDLE
        ctypes.windll.kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        ctypes.windll.psapi.GetProcessMemoryInfo.argtypes = (wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD)
        handle = ctypes.windll.kernel32.OpenProcess(0x410, False, pid)
        if not handle:
            return None
        try:
            counters = Counters(); counters.cb = ctypes.sizeof(counters)
            if not ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                return None
            return round(counters.WorkingSetSize / 1024 / 1024, 2)
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    for line in Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith('VmRSS:'):
            return round(int(line.split()[1]) / 1024, 2)
    return None


def make_synthetic(output, font_path):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
    import zxingcpp
    font = ImageFont.truetype(str(font_path), 45)
    image = Image.new('RGB', (1800, 1200), 'white')
    draw = ImageDraw.Draw(image)
    for index, text in enumerate([
        '软件测试专用 · 无效凭证', '增值税普通发票', '发票号码：20261026000000012345',
        '开票日期：2026年10月26日', '购买方名称：贵州四方馆测试酒店有限公司',
        '购买方纳税人识别号：91520100MA6TEST1234', '销售方名称：测试供应商有限公司',
        '价税合计（小写）：￥128.50', '付款金额：128.50元',
    ]):
        draw.text((70, 45 + index * 105), text, font=font, fill='black')
    code = zxingcpp.create_barcode('SFG_SYNTHETIC_INVOICE_20261026', zxingcpp.BarcodeFormat.QRCode)
    qr = Image.fromarray(np.asarray(code.to_image(scale=8))).convert('RGB')
    image.paste(qr, (1450, 800))
    buffer = io.BytesIO(); image.save(buffer, format='JPEG', quality=94)
    data = buffer.getvalue()
    image.close(); qr.close()
    output.mkdir(parents=True, exist_ok=True)
    filename = output / 'synthetic-chinese-invoice.jpg'
    with filename.open('xb') as stream:
        stream.write(data)
    return filename, data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/msyh.ttc'))
    parser.add_argument('--serve', action='store_true')
    parser.add_argument('--port', type=int, default=8095)
    arguments = parser.parse_args()
    filename, content = make_synthetic(arguments.output, arguments.font)
    models = verified_models()
    worker = Worker(models)
    server = None
    try:
        started = time.monotonic(); worker.start()
        while worker.health() != 'READY' and time.monotonic() - started < 125:
            time.sleep(0.05)
        if worker.health() != 'READY':
            raise RuntimeError('Actual offline engine warmup did not succeed')
        warmup_seconds = time.monotonic() - started
        started = time.monotonic()
        result = worker.run({'sourceSha256': hashlib.sha256(content).hexdigest(), 'content': content, 'mediaType': 'image/jpeg'})
        elapsed = time.monotonic() - started
        texts = '\n'.join(block['text'] for block in result['blocks'])
        checks = {'chineseText': '发票' in texts, 'invoiceNumber': '20261026000000012345' in texts,
                  'amount': '128.50' in texts, 'buyerTaxId': '91520100MA6TEST1234' in texts,
                  'qrDecoded': result['qrCodes'] == ['SFG_SYNTHETIC_INVOICE_20261026'],
                  'sourceHash': result['sourceSha256'] == hashlib.sha256(content).hexdigest()}
        metrics = {'status': 'PASS' if all(checks.values()) else 'FAIL', 'syntheticOnly': True,
                   'sourceImage': str(filename.resolve()), 'imageBytes': len(content), 'blocks': len(result['blocks']),
                   'warmupSeconds': round(warmup_seconds, 3), 'recognitionSeconds': round(elapsed, 3),
                   'workerRssMiB': memory_mib(worker.process.pid), 'checks': checks, 'warnings': result['warnings']}
        print(json.dumps(metrics), flush=True)
        if not all(checks.values()):
            raise RuntimeError('Actual synthetic OCR check failed')
        if arguments.serve:
            server = Server(('127.0.0.1', arguments.port), worker, TEST_TOKEN)
            print(json.dumps({'listening': f'http://127.0.0.1:{server.server_port}', 'syntheticOnly': True}), flush=True)
            server.serve_forever(poll_interval=0.25)
    finally:
        if server:
            server.server_close()
        worker.close()


if __name__ == '__main__':
    main()
