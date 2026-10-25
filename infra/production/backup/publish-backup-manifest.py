#!/usr/bin/env python3
"""Publish an atomic, allowlisted manifest for the read-only backup account."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import NoReturn


ROOT = Path("/srv/sfg-backup-export")
ALLOWED = (
    re.compile(r"hotel-ai-os/daily/hotel_ai_os-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}(?:-[A-Za-z0-9][A-Za-z0-9_-]{0,80})?[.]dump[.]enc"),
    re.compile(r"hotel-ai-os/monthly/hotel_ai_os-monthly-[0-9]{6}[.]dump[.]enc"),
    re.compile(r"hotel-ai-os/yearly/hotel_ai_os-yearly-[0-9]{4}[.]dump[.]enc"),
    re.compile(r"sifangguan-ota/daily/sifangguan-ota-state-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}[.]tar[.]gz[.]enc"),
    re.compile(r"sifangguan-ota/monthly/sifangguan-ota-state-monthly-[0-9]{6}[.]tar[.]gz[.]enc"),
    re.compile(r"sifangguan-ota/yearly/sifangguan-ota-state-yearly-[0-9]{4}[.]tar[.]gz[.]enc"),
    re.compile(r"analytics-raw/daily/analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-[0-9a-f]{64})?[.]tar[.]gz[.]enc"),
    re.compile(r"sifangguan-ota/daily/sifangguan-ota-state-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}[.]snapshot[.]json[.]enc"),
    re.compile(r"sifangguan-ota/monthly/sifangguan-ota-state-monthly-[0-9]{6}[.]snapshot[.]json[.]enc"),
    re.compile(r"sifangguan-ota/yearly/sifangguan-ota-state-yearly-[0-9]{4}[.]snapshot[.]json[.]enc"),
    re.compile(r"sifangguan-ota/objects/[0-9a-f]{64}[.]pack[.]enc"),
)
HEX_256 = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_atomic(path: Path, payload: bytes, gid: int) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o440)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chown(temporary, 0, gid)
        os.chmod(temporary, 0o440)
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def validate_recovery_points(entries, selection):
    """No key/plaintext original metadata is ever exported to the device."""
    by_path = {item["path"]: item for item in entries}
    roots = set()
    objects = set()
    result = selection.get("recoveryPoints", []) if selection else []
    for point in result:
        state = by_path.get(point.get("statePath"))
        database = by_path.get(point.get("databasePath"))
        if not state or not database or not str(state["path"]).endswith(".snapshot.json.enc"):
            fail("recovery point is missing a state/database artifact")
        if state["sha256"] != point.get("stateSha256") or database["sha256"] != point.get("databaseSha256"):
            fail("recovery point pair digest mismatch")
        if not re.fullmatch(r"[0-9]{8}T[0-9]{6}[+-][0-9]{4}", str(point.get("snapshotId", ""))):
            fail("invalid recovery point snapshot")
        if point["statePath"] in roots or state["tier"] != database["tier"]:
            fail("duplicate or cross-tier recovery point")
        roots.add(point["statePath"])
        dependencies = point.get("objects", [])
        if not dependencies or len(set(dependencies)) != len(dependencies):
            fail("invalid recovery closure")
        for path in dependencies:
            entry = by_path.get(path)
            if not entry or not re.fullmatch(r"sifangguan-ota/objects/[0-9a-f]{64}[.]pack[.]enc", path):
                fail("missing or invalid recovery object")
            if Path(path).name[:64] != entry["sha256"]:
                fail("content-addressed object digest mismatch")
            objects.add(path)
    expected_roots = {x["path"] for x in entries if str(x["path"]).endswith(".snapshot.json.enc")}
    expected_objects = {x["path"] for x in entries if x["tier"] == "objects"}
    if roots != expected_roots or objects != expected_objects:
        fail("unbound snapshot/object export is not recoverable")
    return result


def main() -> None:
    import grp  # Unix only; pure manifest validation remains portable in tests.
    if len(sys.argv) != 3:
        fail("usage: publish-backup-manifest.py EXPORT_ROOT EXPORT_GROUP")
    root = Path(sys.argv[1]).resolve(strict=True)
    if root != ROOT:
        fail("unsafe export root")
    group = grp.getgrnam(sys.argv[2])
    data_root = root / "data"
    if not data_root.is_dir() or data_root.is_symlink():
        fail("missing safe export data directory")

    selection_path = root / ".recovery-closure.json"
    selection = None
    selected = None
    if selection_path.exists():
        if selection_path.is_symlink() or not selection_path.is_file():
            fail("unsafe recovery closure")
        selection = json.loads(selection_path.read_text(encoding="ascii"))
        if selection.get("kind") != "SFG_RECOVERY_CLOSURE_V2":
            fail("invalid recovery closure")
        selected = set(selection.get("artifactPaths", []))
        if not selected or len(selected) != len(selection["artifactPaths"]):
            fail("empty or duplicate export selection")
    entries: list[dict[str, object]] = []
    for path in sorted(data_root.rglob("*.enc")):
        relative = path.relative_to(data_root).as_posix()
        if not relative.isascii() or not any(rule.fullmatch(relative) for rule in ALLOWED):
            fail(f"unapproved export artifact: {relative}")
        if selected is not None and relative not in selected:
            continue  # Previous accepted manifest remains usable until publish.
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or path.is_symlink():
            fail(f"unsafe export artifact: {relative}")
        checksum_path = path.with_name(path.name + ".sha256")
        checksum_metadata = checksum_path.lstat()
        if not stat.S_ISREG(checksum_metadata.st_mode) or checksum_path.is_symlink():
            fail(f"unsafe checksum: {relative}")
        checksum_fields = checksum_path.read_text(encoding="ascii").strip().split()
        recorded_name = Path(checksum_fields[1].lstrip("*")).name if len(checksum_fields) == 2 else ""
        if len(checksum_fields) != 2 or recorded_name != path.name:
            fail(f"invalid checksum record: {relative}")
        expected = checksum_fields[0].lower()
        if not HEX_256.fullmatch(expected) or hash_file(path) != expected:
            fail(f"checksum mismatch: {relative}")
        raw_revision = re.fullmatch(r"analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}-([0-9a-f]{64})[.]tar[.]gz[.]enc", path.name)
        if raw_revision and raw_revision[1] != expected:
            fail(f"immutable raw revision digest mismatch: {relative}")
        category, tier, _ = relative.split("/", 2)
        entries.append(
            {
                "path": relative,
                "category": category,
                "tier": tier,
                "bytes": metadata.st_size,
                "sha256": expected,
            }
        )

    required = {"hotel-ai-os", "sifangguan-ota", "analytics-raw"}
    present = {str(entry["category"]) for entry in entries}
    if not entries or not required.issubset(present):
        fail("manifest is missing a required backup category")
    if selected is not None and selected != {item["path"] for item in entries}:
        fail("missing selected export artifact")
    if len(entries) > 200000:
        fail("export manifest exceeds bounded device capacity")
    recovery = validate_recovery_points(entries, selection)

    entries_bytes = json.dumps(
        entries, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    manifest_id = hashlib.sha256(entries_bytes).hexdigest()
    receipt_relative = f"receipts/{manifest_id}.ok"
    receipt = {
        "kind": "SFG_BACKUP_RECEIPT_TOKEN_V1",
        "manifestId": manifest_id,
        "instruction": "download only after every manifest entry passed local size and SHA-256 verification",
    }
    receipt_bytes = (json.dumps(receipt, ensure_ascii=True, indent=2) + "\n").encode("ascii")
    write_atomic(root / receipt_relative, receipt_bytes, group.gr_gid)

    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=14)
    for old_receipt in (root / "receipts").glob("*.ok"):
        if old_receipt.name == f"{manifest_id}.ok" or old_receipt.is_symlink():
            continue
        modified = dt.datetime.fromtimestamp(old_receipt.stat().st_mtime, dt.timezone.utc)
        if modified < cutoff:
            old_receipt.unlink()

    manifest = {
        "kind": "SFG_BACKUP_MANIFEST_V1",
        "schemaVersion": 1,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "manifestId": manifest_id,
        "retention": {
            "database": {"daily": 30, "monthly": 12, "yearly": 3},
            "sifangguanOtaState": {"daily": 30, "monthly": 12, "yearly": 3},
            "rawEvidenceDays": 90,
        },
        "receiptPath": receipt_relative,
        "entries": entries,
        "recoveryPoints": recovery,
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=True, indent=2) + "\n").encode("ascii")
    if len(manifest_bytes) > 128 * 1024 * 1024:
        fail("export manifest exceeds device byte limit")
    write_atomic(root / "manifest-v1.json", manifest_bytes, group.gr_gid)
    # Publication is the commit point. No old dependency is removed on a failed
    # copy/validation/publication. An in-flight stale downloader fails closed.
    if selected is not None:
        for path in data_root.rglob("*.enc"):
            relative = path.relative_to(data_root).as_posix()
            if relative not in selected:
                if path.is_symlink() or not path.is_file():
                    fail("unsafe stale export artifact")
                path.unlink()
                path.with_name(path.name + ".sha256").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
