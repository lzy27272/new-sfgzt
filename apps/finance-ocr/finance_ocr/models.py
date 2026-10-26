"""Only the three model artifacts licensed and bundled by RapidOCR 3.10.0."""
import hashlib
from importlib.metadata import distribution

MODELS = {
    "det": ("PP-OCRv6_det_small.onnx", "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f"),
    "rec": ("PP-OCRv6_rec_small.onnx", "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884"),
    "cls": ("ch_ppocr_mobile_v2.0_cls_mobile.onnx", "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c"),
}


def verified_models():
    package = distribution("rapidocr")
    if package.version != "3.10.0":
        raise RuntimeError("Unsupported OCR package version")
    files = list(package.files or ())
    paths = {}
    for kind, (name, expected) in MODELS.items():
        candidates = [package.locate_file(path) for path in files if str(path).replace("\\", "/").endswith("/" + name)]
        if len(candidates) != 1:
            raise RuntimeError("Required bundled OCR model is missing")
        path = candidates[0]
        if path.is_symlink() or not path.is_file():
            raise RuntimeError("Unsafe OCR model path")
        with path.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                raise RuntimeError("Bundled OCR model checksum differs")
        paths[kind] = str(path.resolve())
    return paths
