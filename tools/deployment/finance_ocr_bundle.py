"""Build and verify a commit-bound, offline Linux OCR payload (stdlib only)."""
import argparse
import ast
import email
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

SHA = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
SMOKE_CHECKS = {"chineseText", "invoiceNumber", "amount", "buyerTaxId", "qrDecoded", "sourceHash"}
REQUIRED_FILES = {
    "app/finance_ocr/__init__.py", "app/finance_ocr/__main__.py",
    "app/finance_ocr/engine.py", "app/finance_ocr/models.py", "app/finance_ocr/server.py",
    "app/requirements.lock", "deployment/hotel-ai-os-finance-ocr.service",
    "deployment/README.md", "deployment/finance_ocr_bundle.py",
    "evidence/linux-smoke.json", "evidence/unittest.log",
}
MAX_FILES = 256
MAX_TOTAL = 1024 * 1024 * 1024


def digest(content):
    return hashlib.sha256(content).hexdigest()


def canonical_name(name):
    return re.sub(r"[-_.]+", "-", name).lower()


def safe_path(name):
    path = PurePosixPath(name)
    if (not isinstance(name, str) or not name or "\\" in name or ":" in name
            or name.startswith("/") or path.as_posix() != name
            or any(part in ("", ".", "..") for part in name.split("/"))):
        raise ValueError("Unsafe OCR bundle path")
    return name


def unique_json(content):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate OCR JSON property")
            result[key] = value
        return result
    return json.loads(content, object_pairs_hook=pairs)


def locked_packages(content):
    packages = {}
    for line in content.decode("utf-8").replace("\\\r\n", " ").replace("\\\n", " ").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line in ("--only-binary=:all:", "--require-hashes"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([^\s]+)((?:\s+--hash=sha256:[0-9a-f]{64})+)", line)
        if not match:
            raise ValueError("OCR requirements must contain only hashed, exact package pins")
        name = canonical_name(match[1])
        if name in packages:
            raise ValueError("Duplicate OCR package pin")
        packages[name] = (match[2], set(re.findall(r"--hash=sha256:([0-9a-f]{64})", match[3])))
    if not packages or "rapidocr" not in packages:
        raise ValueError("OCR lock is empty or missing RapidOCR")
    return packages


def model_contract(content):
    tree = ast.parse(content.decode("utf-8"))
    assignments = [node for node in tree.body if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "MODELS" for target in node.targets)]
    if len(assignments) != 1:
        raise ValueError("OCR model contract is missing")
    models = ast.literal_eval(assignments[0].value)
    if set(models) != {"det", "rec", "cls"}:
        raise ValueError("OCR model roles differ")
    result = []
    for kind, pair in sorted(models.items()):
        name, sha = pair
        if safe_path(name) != PurePosixPath(name).name or not SHA.fullmatch(sha):
            raise ValueError("OCR model identity is invalid")
        result.append({"kind": kind, "file": name, "sha256": sha})
    return result


def verify_payload(files, manifest, source_commit):
    if not COMMIT.fullmatch(source_commit):
        raise ValueError("Invalid expected OCR source commit")
    if (manifest.get("schemaVersion") != 1 or manifest.get("status") != "PASS"
            or manifest.get("sourceCommit") != source_commit
            or manifest.get("pythonMajorMinor") != "3.12"
            or manifest.get("platform") != "linux-x86_64"):
        raise ValueError("OCR bundle commit, platform or schema differs")
    if not REQUIRED_FILES.issubset(files):
        raise ValueError("Required OCR bundle file is missing")
    for name in files:
        safe_path(name)
        if not (name in REQUIRED_FILES or re.fullmatch(r"wheels/[A-Za-z0-9_.+-]+\.whl", name)):
            raise ValueError("Unexpected OCR bundle file")
    records = manifest.get("files")
    if not isinstance(records, list) or len(records) != len(files):
        raise ValueError("OCR bundle inventory differs")
    seen = set()
    for entry in records:
        if not isinstance(entry, dict) or set(entry) != {"file", "sha256", "sizeBytes"}:
            raise ValueError("Invalid OCR file descriptor")
        name = safe_path(entry["file"])
        if name in seen or name not in files or not isinstance(entry["sha256"], str) or not SHA.fullmatch(entry["sha256"]):
            raise ValueError("Duplicate or invalid OCR file descriptor")
        seen.add(name)
        if type(entry["sizeBytes"]) is not int or entry["sizeBytes"] != len(files[name]) or entry["sha256"] != digest(files[name]):
            raise ValueError("OCR bundle file hash or size differs")
    if manifest.get("requirementsSha256") != digest(files["app/requirements.lock"]):
        raise ValueError("OCR requirements hash differs")
    models = model_contract(files["app/finance_ocr/models.py"])
    if manifest.get("models") != models:
        raise ValueError("OCR declared model identity differs")
    smoke = unique_json(files["evidence/linux-smoke.json"])
    if (smoke.get("status") != "PASS" or smoke.get("syntheticOnly") is not True
            or set(smoke.get("checks", {})) != SMOKE_CHECKS
            or any(value is not True for value in smoke["checks"].values())):
        raise ValueError("OCR actual synthetic engine evidence failed")
    unit_log = files["evidence/unittest.log"].decode("utf-8")
    if (not re.search(r"Ran [1-9][0-9]* tests? in ", unit_log)
            or not re.search(r"\nOK\s*\Z", unit_log) or "skipped=" in unit_log or "FAILED" in unit_log):
        raise ValueError("OCR unit test completion evidence is missing")
    packages = locked_packages(files["app/requirements.lock"])
    found = set()
    rapid_wheel = None
    for name, content in files.items():
        if not name.startswith("wheels/"):
            continue
        if any(token in name.lower() for token in ("win32", "win_amd64", "macosx", "aarch64", "musllinux")):
            raise ValueError("OCR wheel is not the validated Linux x86_64 target")
        import io
        with zipfile.ZipFile(io.BytesIO(content)) as wheel:
            metadata = [entry for entry in wheel.namelist() if entry.endswith(".dist-info/METADATA")]
            if len(metadata) != 1:
                raise ValueError("OCR wheel metadata is invalid")
            document = email.message_from_bytes(wheel.read(metadata[0]))
            package = canonical_name(document["Name"] or "")
            if (package not in packages or package in found or document["Version"] != packages[package][0]
                    or digest(content) not in packages[package][1]):
                raise ValueError("OCR wheel is duplicated or does not match the hash lock")
            found.add(package)
            if package == "rapidocr":
                rapid_wheel = name
                for model in models:
                    candidates = [entry for entry in wheel.namelist() if entry.endswith("/" + model["file"])]
                    if len(candidates) != 1 or digest(wheel.read(candidates[0])) != model["sha256"]:
                        raise ValueError("OCR bundled model hash differs")
    if found != set(packages) or rapid_wheel is None:
        raise ValueError("OCR offline wheel closure is incomplete")
    return {"status": "PASS", "sourceCommit": source_commit,
            "pythonMajorMinor": "3.12", "platform": "linux-x86_64",
            "requirementsSha256": manifest["requirementsSha256"], "wheelCount": len(found),
            "fileCount": len(files), "models": models}


def verify_bundle(bundle, source_commit):
    if bundle.is_symlink() or not bundle.is_file():
        raise ValueError("OCR bundle must be a regular file")
    files = {}
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > MAX_FILES or sum(entry.file_size for entry in entries) > MAX_TOTAL:
            raise ValueError("OCR bundle size limit exceeded")
        names = set()
        for entry in entries:
            name = safe_path(entry.filename)
            mode = entry.external_attr >> 16
            if name.casefold() in names or entry.is_dir() or (stat.S_IFMT(mode) not in (0, stat.S_IFREG)) or entry.flag_bits & 1:
                raise ValueError("OCR bundle duplicate, link or nonregular entry")
            names.add(name.casefold())
            if name == "bundle-manifest.json" and entry.file_size > 1024 * 1024:
                raise ValueError("OCR manifest size limit exceeded")
            files[name] = archive.read(entry)
    if "bundle-manifest.json" not in files:
        raise ValueError("OCR inner manifest is missing")
    manifest_bytes = files.pop("bundle-manifest.json")
    result = verify_payload(files, unique_json(manifest_bytes), source_commit)
    with bundle.open("rb") as stream:
        bundle_sha = hashlib.file_digest(stream, "sha256").hexdigest()
    result.update({"bundleManifestSha256": digest(manifest_bytes), "bundleSha256": bundle_sha})
    return result


def checked_verify(bundle, source_commit, manifest_sha=None, requirements_sha=None):
    result = verify_bundle(bundle, source_commit)
    for expected, field in ((manifest_sha, "bundleManifestSha256"), (requirements_sha, "requirementsSha256")):
        if expected is not None and (not SHA.fullmatch(expected) or result[field] != expected):
            raise ValueError("OCR expected release manifest identity differs")
    return result


def extract_bundle(bundle, destination, source_commit, manifest_sha=None, requirements_sha=None):
    result = checked_verify(bundle, source_commit, manifest_sha, requirements_sha)
    # Caller supplies a new directory within its private staging root. Never merge/overwrite.
    destination.mkdir(mode=0o700)
    with zipfile.ZipFile(bundle) as archive:
        manifest_bytes = archive.read("bundle-manifest.json")
        if digest(manifest_bytes) != result["bundleManifestSha256"]:
            raise ValueError("OCR bundle changed before extraction")
        manifest = unique_json(manifest_bytes)
        for entry in manifest["files"] + [{"file": "bundle-manifest.json", "sha256": digest(manifest_bytes), "sizeBytes": len(manifest_bytes)}]:
            name = safe_path(entry["file"])
            content = archive.read(name)
            if len(content) != entry["sizeBytes"] or digest(content) != entry["sha256"]:
                raise ValueError("OCR bundle changed during extraction")
            target = destination.joinpath(*PurePosixPath(name).parts)
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with target.open("xb") as stream:
                stream.write(content)
            target.chmod(0o644)
    return result


def regular_bytes(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("OCR build input must be a regular file")
    return path.read_bytes()


def build_bundle(source_root, wheelhouse, evidence_root, source_commit, output):
    head = subprocess.run(["git", "-C", str(source_root), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if not COMMIT.fullmatch(source_commit) or head != source_commit:
        raise ValueError("OCR build source must match the exact checkout commit")
    if output.exists():
        raise ValueError("Refusing to overwrite an OCR bundle")
    if sys.platform != "linux" or sys.version_info[:2] != (3, 12) or os.uname().machine != "x86_64":
        raise ValueError("OCR deployable bundle must be tested and built on Linux x86_64 Python 3.12")
    files = {}
    for name in sorted(REQUIRED_FILES):
        if name.startswith("app/"):
            path = source_root / "apps/finance-ocr" / name.removeprefix("app/")
        elif name.startswith("evidence/"):
            path = evidence_root / name.removeprefix("evidence/")
        elif name == "deployment/finance_ocr_bundle.py":
            path = Path(__file__)
        else:
            path = source_root / "infra/production/finance-ocr" / name.removeprefix("deployment/")
        files[name] = regular_bytes(path)
        if not name.startswith("evidence/"):
            relative = path.resolve().relative_to(source_root).as_posix()
            committed = subprocess.run(["git", "-C", str(source_root), "show", source_commit + ":" + relative],
                                       check=True, capture_output=True).stdout
            if committed != files[name]:
                raise ValueError("OCR packaged source differs from the exact checkout commit")
    for path in sorted(wheelhouse.iterdir()):
        if path.suffix != ".whl":
            raise ValueError("Unexpected non-wheel file in offline wheelhouse")
        files["wheels/" + safe_path(path.name)] = regular_bytes(path)
    manifest = {"schemaVersion": 1, "status": "PASS", "sourceCommit": source_commit,
                "pythonMajorMinor": "3.12", "platform": "linux-x86_64",
                "requirementsSha256": digest(files["app/requirements.lock"]),
                "models": model_contract(files["app/finance_ocr/models.py"]),
                "files": [{"file": name, "sha256": digest(content), "sizeBytes": len(content)}
                          for name, content in sorted(files.items())]}
    verify_payload(files, manifest, source_commit)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".ocr-", suffix=".zip", delete=False) as stream:
            temporary = Path(stream.name)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, content in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                entry.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(entry, content)
            entry = zipfile.ZipInfo("bundle-manifest.json", date_time=(2026, 1, 1, 0, 0, 0))
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(entry, json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode() + b"\n")
        result = verify_bundle(temporary, source_commit)
        # Same-volume hard link publishes atomically and never replaces existing data.
        os.link(temporary, output)
        return result
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    build.add_argument("--source-root", type=Path, required=True)
    build.add_argument("--wheelhouse", type=Path, required=True)
    build.add_argument("--evidence-root", type=Path, required=True)
    build.add_argument("--source-commit", required=True)
    build.add_argument("--output", type=Path, required=True)
    for command in ("verify", "extract"):
        verify = commands.add_parser(command)
        verify.add_argument("--bundle", type=Path, required=True)
        verify.add_argument("--source-commit", required=True)
        verify.add_argument("--manifest-sha")
        verify.add_argument("--requirements-sha")
        if command == "extract":
            verify.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            result = build_bundle(args.source_root.resolve(), args.wheelhouse, args.evidence_root, args.source_commit, args.output)
        elif args.command == "extract":
            result = extract_bundle(args.bundle, args.destination, args.source_commit, args.manifest_sha, args.requirements_sha)
        else:
            result = checked_verify(args.bundle, args.source_commit, args.manifest_sha, args.requirements_sha)
        print(json.dumps(result, sort_keys=True))
    except Exception as error:
        # Paths are build inputs, never receipt contents, tokens or request bodies.
        print(f"OCR_BUNDLE_INVALID: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
