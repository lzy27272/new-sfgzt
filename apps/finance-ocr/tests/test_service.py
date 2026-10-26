import base64
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import socket
import struct
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zlib

from finance_ocr.engine import ImageError, decode_image, recognize
from finance_ocr.server import MAX_BODY, RequestError, Server, Worker, parse_payload

TOKEN = "synthetic-test-token-not-a-real-secret-0001"


def envelope(content=b"synthetic bytes", media="image/jpeg", kind="INVOICE"):
    return {"sourceSha256": hashlib.sha256(content).hexdigest(), "mediaType": media,
            "contentBase64": base64.b64encode(content).decode("ascii"), "kind": kind}


class PayloadTests(unittest.TestCase):
    def parse(self, value):
        return parse_payload(json.dumps(value).encode())

    def test_original_bytes_and_sha_preserved(self):
        for kind in ("INVOICE", "TRANSACTION", "PAYMENT"):
            result = self.parse(envelope(kind=kind))
            self.assertEqual(result["content"], b"synthetic bytes")
            self.assertEqual(result["sourceSha256"], hashlib.sha256(result["content"]).hexdigest())

    def test_hash_drift_rejected(self):
        value = envelope(); value["sourceSha256"] = "0" * 64
        with self.assertRaises(RequestError) as error:
            self.parse(value)
        self.assertEqual(error.exception.code, "SOURCE_HASH_MISMATCH")

    def test_unknown_url_or_path_fields_rejected(self):
        for name in ("url", "path", "model_path", "callback"):
            value = envelope(); value[name] = "https://example.invalid/private"
            with self.subTest(name=name), self.assertRaises(RequestError):
                self.parse(value)

    def test_url_not_accepted_as_base64(self):
        value = envelope(); value["contentBase64"] = "https://example.invalid/image.png"
        with self.assertRaises(RequestError):
            self.parse(value)

    def test_invalid_kind_mime_types_or_sha(self):
        for key, value in (("kind", "OTHER"), ("mediaType", "application/pdf"), ("sourceSha256", "A" * 64),
                           ("contentBase64", []), ("contentBase64", "data:image/png;base64,AAAA")):
            request = envelope(); request[key] = value
            with self.subTest(key=key), self.assertRaises(RequestError):
                self.parse(request)

    def test_duplicate_json_empty_or_non_object_rejected(self):
        for raw in (b"[]", b"null", b"", b'{"kind":"INVOICE","kind":"PAYMENT"}'):
            with self.subTest(raw=raw), self.assertRaises(RequestError):
                parse_payload(raw)

    def test_content_limit_checked_after_and_before_decode(self):
        with patch("finance_ocr.server.MAX_CONTENT", 2):
            with self.assertRaises(RequestError) as error:
                self.parse(envelope(b"abc"))
        self.assertEqual(error.exception.status, 413)


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.worker = Mock()
        self.worker.health.return_value = "READY"
        self.worker.run.return_value = {"provider": "RapidOCR", "sourceSha256": envelope()["sourceSha256"],
                                        "blocks": [], "qrCodes": [], "imageHash": {}, "warnings": []}
        self.server = Server(("127.0.0.1", 0), self.worker, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(1)

    def request(self, path="/recognize", body=None, headers=None, method="POST"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        request_headers = {"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN}
        if headers:
            request_headers.update(headers)
        connection.request(method, path, body=body, headers=request_headers)
        response = connection.getresponse()
        value = json.loads(response.read())
        connection.close()
        return response.status, value

    def test_authenticated_request_and_no_content_logging(self):
        with patch("sys.stderr", new_callable=io.StringIO) as stderr:
            status, value = self.request(body=json.dumps(envelope()))
        self.assertEqual(status, 200); self.assertEqual(value["provider"], "RapidOCR")
        self.assertEqual(stderr.getvalue(), "")

    def test_token_required_before_body(self):
        status, value = self.request(body=json.dumps(envelope()), headers={"Authorization": "Bearer wrong"})
        self.assertEqual((status, value["code"]), (401, "UNAUTHORIZED")); self.worker.run.assert_not_called()

    def test_health_contains_only_safe_readiness(self):
        self.assertEqual(self.request(path="/health", method="GET"), (200, {"status": "READY", "provider": "RapidOCR"}))
        self.worker.health.return_value = "WARMING_UP"
        self.assertEqual(self.request(path="/health", method="GET")[0], 503)

    def test_body_size_and_unknown_paths_rejected(self):
        self.assertEqual(self.request(body="{}", headers={"Content-Length": str(MAX_BODY + 1)})[0], 413)
        self.assertEqual(self.request(path="/recognize?url=http://example.invalid", body="{}")[0], 404)

    def test_error_never_echoes_evidence(self):
        self.worker.run.side_effect = RuntimeError("sensitive invoice contents")
        status, result = self.request(body=json.dumps(envelope()))
        self.assertEqual(status, 500); self.assertNotIn("sensitive", json.dumps(result))

    def test_timeout_and_busy_codes_preserved(self):
        for status, code in ((504, "OCR_TIMEOUT"), (429, "OCR_BUSY")):
            self.worker.run.side_effect = RequestError(status, code)
            self.assertEqual(self.request(body=json.dumps(envelope()))[0], status)

    def test_header_handler_bound(self):
        held = []
        try:
            for _ in range(4):
                connection = socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2)
                connection.sendall(b"POST /recognize HTTP/1.0\r\n")
                held.append(connection)
            deadline = time.monotonic() + 1
            while self.server.handlers._value and time.monotonic() < deadline:
                time.sleep(0.01)
            try:
                status, result = self.request(path="/health", method="GET")
                self.assertEqual(status, 503); self.assertEqual(result["code"], "OCR_BUSY")
            except (ConnectionResetError, ConnectionAbortedError, http.client.RemoteDisconnected):
                # An overloaded listener may close before the peer consumes its early 503.
                pass
            self.assertEqual(self.server.handlers._value, 0)
        finally:
            for connection in held:
                connection.close()


class WorkerTests(unittest.TestCase):
    def test_only_one_job_no_queue(self):
        worker = Worker({})
        worker.lock.acquire()
        try:
            with self.assertRaises(RequestError) as error:
                worker.run({})
            self.assertEqual(error.exception.status, 429)
        finally:
            worker.lock.release()

    def test_not_ready_returns_503_not_fake_success(self):
        worker = Worker({})
        with patch.object(worker, "start") as start, self.assertRaises(RequestError) as error:
            worker.run({})
        self.assertEqual(error.exception.status, 503); start.assert_called_once()

    def test_timeout_covers_blocked_ipc_send_and_reaps_process(self):
        worker = Worker({}, timeout=0.02)
        worker.ready = True
        worker.process = Mock(); worker.process.is_alive.return_value = True
        worker.connection = Mock()
        released = threading.Event()
        worker.connection.send.side_effect = lambda _: released.wait(1)
        def stop():
            worker.ready = False
            released.set()
        with patch.object(worker, "stop_process", side_effect=stop) as terminate, patch.object(worker, "start") as restart:
            started = time.monotonic()
            with self.assertRaises(RequestError) as error:
                worker.run({})
        self.assertEqual(error.exception.status, 504); self.assertLess(time.monotonic() - started, 0.5)
        terminate.assert_called_once(); restart.assert_called_once()


class ImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from PIL import Image
        cls.Image = Image

    def picture(self, format="PNG"):
        image = self.Image.new("RGB", (320, 200), "white")
        output = io.BytesIO(); image.save(output, format=format); image.close()
        return output.getvalue()

    def test_valid_png_jpeg_in_memory_no_original_mutation(self):
        for format, mime in (("PNG", "image/png"), ("JPEG", "image/jpeg")):
            content = self.picture(format); before = hashlib.sha256(content).hexdigest()
            image = decode_image(content, mime)
            self.assertEqual(image.size, (320, 200)); image.close()
            self.assertEqual(hashlib.sha256(content).hexdigest(), before)

    def test_mime_spoof_invalid_picture_and_remote_text_rejected(self):
        for content, mime in ((self.picture(), "image/jpeg"), (b"https://example.invalid/a.png", "image/png"), (b"bad", "image/jpeg")):
            with self.subTest(mime=mime), self.assertRaises(ImageError):
                decode_image(content, mime)

    def test_pixel_bomb_rejected_before_decode_allocation(self):
        def chunk(name, value):
            return struct.pack(">I", len(value)) + name + value + struct.pack(">I", zlib.crc32(name + value))
        content = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 10001, 4000, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"")) + chunk(b"IEND", b"")
        with self.assertRaises(ImageError) as error:
            decode_image(content, "image/png")
        self.assertEqual(error.exception.code, "IMAGE_TOO_LARGE")

    def test_real_hashes_and_no_text_warning(self):
        engine = lambda _: SimpleNamespace(txts=None, scores=None)
        data = self.picture()
        result = recognize(engine, {"content": data, "mediaType": "image/png", "sourceSha256": hashlib.sha256(data).hexdigest()})
        self.assertRegex(result["imageHash"]["phash"], r"^[0-9a-f]{16}$")
        self.assertRegex(result["imageHash"]["dhash"], r"^[0-9a-f]{16}$")
        self.assertEqual(result["warnings"], ["NO_TEXT_FOUND"])

    def test_text_limit_skips_whole_block_without_fabricating_partial_number(self):
        engine = lambda _: SimpleNamespace(txts=["9" * 100001], scores=[0.99])
        data = self.picture()
        with patch('zxingcpp.read_barcodes', return_value=[]):
            result = recognize(engine, {"content": data, "mediaType": "image/png", "sourceSha256": hashlib.sha256(data).hexdigest()})
        self.assertEqual(result['blocks'], []); self.assertIn('TEXT_LIMIT_REACHED', result['warnings'])
        self.assertNotIn('NO_TEXT_FOUND', result['warnings'])

    def test_qr_limit_never_returns_partial_code(self):
        engine = lambda _: SimpleNamespace(txts=None, scores=None)
        data = self.picture()
        codes = [SimpleNamespace(text='x' * 4097, valid=True)] + [SimpleNamespace(text=str(index), valid=True) for index in range(25)]
        with patch('zxingcpp.read_barcodes', return_value=codes):
            result = recognize(engine, {"content": data, "mediaType": "image/png", "sourceSha256": hashlib.sha256(data).hexdigest()})
        self.assertEqual(len(result['qrCodes']), 20); self.assertIn('QR_LIMIT_REACHED', result['warnings'])
        self.assertFalse(any(code.startswith('x') for code in result['qrCodes']))


if __name__ == "__main__":
    unittest.main()
