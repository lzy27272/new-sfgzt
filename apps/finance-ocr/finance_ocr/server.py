"""Small private HTTP boundary: bounded handlers, one inference, no upload persistence."""
import base64
import binascii
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import multiprocessing
import os
import re
import socket
import threading
import time

from .engine import child_main
from .models import verified_models

MAX_CONTENT = 20 * 1024 * 1024
MAX_BODY = 28 * 1024 * 1024


class RequestError(ValueError):
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__(code)


def unique_object(pairs):
    value = {}
    for name, item in pairs:
        if name in value:
            raise ValueError("duplicate key")
        value[name] = item
    return value


def parse_payload(raw):
    try:
        data = json.loads(raw, object_pairs_hook=unique_object)
        if not isinstance(data, dict) or set(data) != {"sourceSha256", "mediaType", "contentBase64", "kind"}:
            raise ValueError("request shape")
        if not all(isinstance(value, str) for value in data.values()):
            raise ValueError("request types")
        if not re.fullmatch(r"[0-9a-f]{64}", data["sourceSha256"]):
            raise ValueError("source hash")
        if data["mediaType"] not in ("image/jpeg", "image/png"):
            raise RequestError(415, "UNSUPPORTED_MEDIA_TYPE")
        if data["kind"] not in ("INVOICE", "TRANSACTION", "PAYMENT"):
            raise ValueError("evidence kind")
        if len(data["contentBase64"]) > 4 * ((MAX_CONTENT + 2) // 3):
            raise RequestError(413, "CONTENT_TOO_LARGE")
        content = base64.b64decode(data["contentBase64"], validate=True)
        if not content or len(content) > MAX_CONTENT:
            raise RequestError(413, "CONTENT_TOO_LARGE")
        if not hmac.compare_digest(hashlib.sha256(content).hexdigest(), data["sourceSha256"]):
            raise RequestError(400, "SOURCE_HASH_MISMATCH")
        return {"sourceSha256": data["sourceSha256"], "mediaType": data["mediaType"], "content": content}
    except RequestError:
        raise
    except (ValueError, UnicodeError, binascii.Error, RecursionError):
        raise RequestError(400, "INVALID_REQUEST") from None


class Worker:
    def __init__(self, paths, timeout=12, startup_timeout=120):
        self.paths, self.timeout, self.startup_timeout = paths, timeout, startup_timeout
        self.lock = threading.Lock()
        self.warmup_lock = threading.Lock()
        self.process = self.connection = None
        self.ready = False
        self.closed = False

    def stop_process(self):
        self.ready = False
        if self.process is not None:
            if self.process.is_alive():
                self.process.terminate()
                self.process.join(0.5)
                if self.process.is_alive():
                    self.process.kill()
                    self.process.join(0.5)
            self.process.close()
            self.process = None
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def start(self):
        if not self.warmup_lock.acquire(blocking=False):
            return
        def warm():
            try:
                with self.lock:
                    if self.closed:
                        return
                    self.stop_process()
                    context = multiprocessing.get_context("spawn")
                    self.connection, child = context.Pipe()
                    self.process = context.Process(target=child_main, args=(child, self.paths), daemon=True)
                    self.process.start()
                    child.close()
                    if not self.connection.poll(self.startup_timeout) or self.connection.recv()[0] != "READY":
                        self.stop_process()
                    else:
                        self.ready = True
            except Exception:
                self.stop_process()
            finally:
                self.warmup_lock.release()
        threading.Thread(target=warm, name="ocr-warmup", daemon=True).start()

    def health(self):
        try:
            return "READY" if self.ready and self.process is not None and self.process.is_alive() else "WARMING_UP"
        except ValueError:
            return "WARMING_UP"

    def run(self, payload):
        if not self.lock.acquire(blocking=False):
            raise RequestError(429, "OCR_BUSY")
        restart = False
        try:
            if self.health() != "READY":
                restart = True
                raise RequestError(503, "OCR_WARMING_UP")
            exchange = []
            completed = threading.Event()
            connection = self.connection
            def rpc():
                try:
                    connection.send(payload)
                    exchange.append(connection.recv())
                except Exception:
                    exchange.append(("UNAVAILABLE", None))
                finally:
                    completed.set()
            rpc_thread = threading.Thread(target=rpc, name="ocr-rpc", daemon=True)
            rpc_thread.start()
            if not completed.wait(self.timeout):
                restart = True
                self.stop_process()
                rpc_thread.join(0.5)
                raise RequestError(504, "OCR_TIMEOUT")
            status, value = exchange[0]
            if status == "INVALID":
                raise RequestError(413 if value == "IMAGE_TOO_LARGE" else 422, value)
            if status != "OK":
                restart = True
                self.stop_process()
                raise RequestError(500, "OCR_FAILED")
            return value
        except (EOFError, BrokenPipeError, OSError):
            restart = True
            self.stop_process()
            raise RequestError(503, "OCR_UNAVAILABLE") from None
        finally:
            self.lock.release()
            if restart:
                self.start()

    def close(self):
        self.closed = True
        with self.lock:
            self.stop_process()


class Handler(BaseHTTPRequestHandler):
    server_version = "FinanceOCR"
    sys_version = ""

    def log_message(self, *_args):
        pass

    def log_error(self, *_args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def respond(self, status, value):
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(raw) > 512 * 1024:
            status, raw = 500, b'{"code":"OCR_RESULT_TOO_LARGE"}'
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if status in (429, 503):
            self.send_header("Retry-After", "2")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path != "/health":
            self.respond(404, {"code": "NOT_FOUND"})
            return
        status = self.server.worker.health()
        self.respond(200 if status == "READY" else 503, {"status": status, "provider": "RapidOCR"})

    def do_POST(self):
        try:
            if self.path != "/recognize":
                raise RequestError(404, "NOT_FOUND")
            authorization = self.headers.get_all("Authorization", [])
            if len(authorization) != 1 or not hmac.compare_digest(authorization[0].encode("utf-8"), ("Bearer " + self.server.token).encode("utf-8")):
                raise RequestError(401, "UNAUTHORIZED")
            if self.headers.get_content_type() != "application/json" or self.headers.get("Transfer-Encoding"):
                raise RequestError(400, "INVALID_REQUEST")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,9}", lengths[0]):
                raise RequestError(411, "CONTENT_LENGTH_REQUIRED")
            length = int(lengths[0])
            if length < 1 or length > MAX_BODY:
                raise RequestError(413, "CONTENT_TOO_LARGE")
            deadline = time.monotonic() + 10
            pieces, remaining = [], length
            while remaining:
                seconds = deadline - time.monotonic()
                if seconds <= 0:
                    raise RequestError(408, "REQUEST_TIMEOUT")
                self.connection.settimeout(min(5, seconds))
                piece = self.rfile.read1(min(65536, remaining))
                if not piece:
                    raise RequestError(400, "INCOMPLETE_REQUEST")
                pieces.append(piece)
                remaining -= len(piece)
            self.respond(200, self.server.worker.run(parse_payload(b"".join(pieces))))
        except RequestError as error:
            self.respond(error.status, {"code": error.code})
        except (socket.timeout, TimeoutError):
            self.respond(408, {"code": "REQUEST_TIMEOUT"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.respond(500, {"code": "OCR_FAILED"})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8
    allow_reuse_address = True

    def __init__(self, address, worker, token):
        self.worker, self.token = worker, token
        self.handlers = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.handlers.acquire(blocking=False):
            try:
                request.sendall(b'HTTP/1.0 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: 19\r\nConnection: close\r\n\r\n{"code":"OCR_BUSY"}')
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.handlers.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.handlers.release()

    def handle_error(self, *_args):
        pass


def main():
    token = os.environ.get("FINANCE_OCR_TOKEN", "")
    if not re.fullmatch(r"[!-~]{32,256}", token):
        raise SystemExit("FINANCE_OCR_TOKEN must contain 32-256 non-space ASCII characters")
    timeout = int(os.environ.get("FINANCE_OCR_TIMEOUT_SECONDS", "12"))
    startup_timeout = int(os.environ.get("FINANCE_OCR_STARTUP_TIMEOUT_SECONDS", "120"))
    if not 1 <= timeout <= 12 or not 10 <= startup_timeout <= 180:
        raise SystemExit("Invalid OCR time limit")
    worker = Worker(verified_models(), timeout, startup_timeout)
    server = Server((os.environ.get("FINANCE_OCR_HOST", "127.0.0.1"), int(os.environ.get("FINANCE_OCR_PORT", "8095"))), worker, token)
    worker.start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
        worker.close()
