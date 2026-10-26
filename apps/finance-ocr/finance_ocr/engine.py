"""Inference runs in a killable child process; it never writes evidence to disk."""
import io
import json
import math
import warnings

MAX_PIXELS = 40_000_000


class ImageError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def decode_image(content, media_type):
    from PIL import Image, ImageOps, UnidentifiedImageError
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    expected = {"image/jpeg": "JPEG", "image/png": "PNG"}[media_type]
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(content)) as image:
                if image.format != expected or getattr(image, "n_frames", 1) != 1:
                    raise ImageError("IMAGE_TYPE_MISMATCH")
                if image.width * image.height > MAX_PIXELS:
                    raise ImageError("IMAGE_TOO_LARGE")
                image.verify()
            with Image.open(io.BytesIO(content)) as image:
                return ImageOps.exif_transpose(image).convert("RGB")
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ImageError("IMAGE_TOO_LARGE") from None
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as error:
        if isinstance(error, ImageError):
            raise
        raise ImageError("INVALID_IMAGE") from None


def recognize(engine, payload):
    import imagehash
    import zxingcpp
    image = decode_image(payload["content"], payload["mediaType"])
    try:
        result = engine(image)
        blocks, alerts = [], []
        text_units, response_bytes = 0, 0
        texts = result.txts if result.txts is not None else ()
        scores = result.scores if result.scores is not None else ()
        for text, confidence in zip(texts, scores):
            score = float(confidence)
            if not math.isfinite(score):
                continue
            text = str(text)
            units = len(text.encode("utf-16-le")) // 2
            block = {"text": text, "confidence": max(0.0, min(1.0, score))}
            block_bytes = len(json.dumps(block, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) + 1
            if text_units + units > 100_000 or response_bytes + block_bytes > 500 * 1024:
                alerts.append("TEXT_LIMIT_REACHED")
                break
            blocks.append(block)
            text_units += units
            response_bytes += block_bytes
            if len(blocks) >= 1000:
                alerts.append("TEXT_LIMIT_REACHED")
                break
        if not blocks and "TEXT_LIMIT_REACHED" not in alerts:
            alerts.append("NO_TEXT_FOUND")
        elif any(block["confidence"] < 0.85 for block in blocks):
            alerts.append("LOW_CONFIDENCE_TEXT")
        codes = []
        for code in zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode):
            if not code.valid or code.text in codes:
                continue
            code_bytes = len(json.dumps(code.text, ensure_ascii=False).encode("utf-8")) + 1
            if len(codes) >= 20 or len(code.text.encode("utf-16-le")) // 2 > 4096 or response_bytes + code_bytes > 500 * 1024:
                if "QR_LIMIT_REACHED" not in alerts:
                    alerts.append("QR_LIMIT_REACHED")
                continue
            codes.append(code.text)
            response_bytes += code_bytes
        return {
            "provider": "RapidOCR", "sourceSha256": payload["sourceSha256"], "blocks": blocks,
            "qrCodes": codes, "imageHash": {"algorithm": "phash-dhash-v1", "phash": str(imagehash.phash(image, hash_size=8)),
                                         "dhash": str(imagehash.dhash(image, hash_size=8))}, "warnings": alerts,
        }
    finally:
        image.close()


def child_main(connection, model_paths):
    import logging
    import os
    import socket
    # Do not let third-party Python/native diagnostics disclose recognized text.
    with open(os.devnull, "w") as sink:
        os.dup2(sink.fileno(), 1)
        os.dup2(sink.fileno(), 2)
    logging.disable(logging.CRITICAL)

    def no_network(*_args, **_kwargs):
        raise RuntimeError("OCR worker network is disabled")

    class OfflineSocket(socket.socket):
        connect = no_network
        connect_ex = no_network
        sendto = no_network
    socket.socket = OfflineSocket
    socket.create_connection = no_network
    socket.getaddrinfo = no_network
    try:
        from PIL import Image, ImageDraw, ImageFont
        from rapidocr import RapidOCR
        engine = RapidOCR(params={
            "Det.model_path": model_paths["det"], "Rec.model_path": model_paths["rec"], "Cls.model_path": model_paths["cls"],
            "Global.log_level": "critical", "EngineConfig.onnxruntime.intra_op_num_threads": 2,
            "EngineConfig.onnxruntime.inter_op_num_threads": 1,
        })
        warmup = Image.new("RGB", (640, 240), "white")
        ImageDraw.Draw(warmup).text((20, 60), "INVOICE 20261026 TOTAL 128.50", font=ImageFont.load_default(size=30), fill="black")
        warmed = engine(warmup)
        warmup.close()
        if warmed.txts is None or len(warmed.txts) == 0:
            raise RuntimeError("OCR pipeline warmup failed")
        connection.send(("READY", None))
        while True:
            payload = connection.recv()
            try:
                connection.send(("OK", recognize(engine, payload)))
            except ImageError as error:
                connection.send(("INVALID", error.code))
            except Exception:
                connection.send(("ERROR", None))
    except (EOFError, BrokenPipeError):
        pass
    except Exception:
        try:
            connection.send(("ERROR", None))
        except (EOFError, BrokenPipeError):
            pass
    finally:
        connection.close()
