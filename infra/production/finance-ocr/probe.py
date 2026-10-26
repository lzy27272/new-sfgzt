"""Private deployment HTTP acceptance using only an in-memory synthetic image."""
import base64
import hashlib
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request


def probe(port):
    from PIL import Image, ImageDraw, ImageFont
    if port not in (8095, 8096):
        raise ValueError("Unsupported private port")
    token = os.environ.get("FINANCE_OCR_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token):
        raise ValueError("Missing protected deployment token")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *_args, **_kwargs):
            raise ValueError("OCR probe redirects are forbidden")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    base = f"http://127.0.0.1:{port}"
    with opener.open(base + "/health", timeout=4) as response:
        health = json.loads(response.read(4097))
    if health != {"status": "READY", "provider": "RapidOCR"}:
        raise ValueError("OCR is not warm")
    image = Image.new("RGB", (1800, 700), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=60)
    for i, text in enumerate(("SYNTHETIC TEST ONLY", "20261026000000012345", "128.50")):
        draw.text((70, 70 + i * 180), text, fill="black", font=font)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    content = buffer.getvalue()
    sha = hashlib.sha256(content).hexdigest()
    body = json.dumps({"sourceSha256": sha, "mediaType": "image/png",
                       "contentBase64": base64.b64encode(content).decode("ascii"), "kind": "INVOICE"}).encode()
    request = urllib.request.Request(base + "/recognize", data=body,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with opener.open(request, timeout=15) as response:
        raw = response.read(524289)
    if len(raw) > 524288:
        raise ValueError("OCR result is oversized")
    value = json.loads(raw)
    blocks = value.get("blocks")
    if not isinstance(blocks, list) or not 1 <= len(blocks) <= 1000:
        raise ValueError("OCR did not return real text blocks")
    text = "\n".join(item.get("text", "") for item in blocks)
    image_hash = value.get("imageHash", {})
    checks = {"provider": value.get("provider") == "RapidOCR",
              "sourceHash": value.get("sourceSha256") == sha,
              "invoiceNumber": "20261026000000012345" in text,
              "amount": "128.50" in text,
              "imageHash": image_hash.get("algorithm") == "phash-dhash-v1" and
                  all(re.fullmatch(r"[0-9a-f]{16}", image_hash.get(key, "")) for key in ("phash", "dhash"))}
    bad = urllib.request.Request(base + "/recognize", data=body,
        headers={"Authorization": "Bearer synthetic-invalid-token-not-a-secret", "Content-Type": "application/json"})
    try:
        opener.open(bad, timeout=4).close()
        checks["authorization"] = False
    except urllib.error.HTTPError as error:
        checks["authorization"] = error.code == 401
    if not all(checks.values()):
        raise ValueError("Synthetic OCR acceptance failed")
    return {"status": "PASS", "syntheticOnly": True, "checks": checks}


if __name__ == "__main__":
    try:
        print(json.dumps(probe(int(sys.argv[1])), sort_keys=True))
    except Exception:
        # Do not serialize HTTP bodies, request headers, tokens or ticket text.
        print("OCR_SYNTHETIC_PROBE_FAILED", file=sys.stderr)
        sys.exit(1)
