#!/usr/bin/env python3
"""Publish an atomic, allowlisted manifest for the read-only backup account."""

from __future__ import annotations

import datetime as dt
import grp
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
    re.compile(r"analytics-raw/daily/analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}[.]tar[.]gz[.]enc"),
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


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: publish-backup-manifest.py EXPORT_ROOT EXPORT_GROUP")
    root = Path(sys.argv[1]).resolve(strict=True)
    if root != ROOT:
        fail("unsafe export root")
    group = grp.getgrnam(sys.argv[2])
    data_root = root / "data"
    if not data_root.is_dir() or data_root.is_symlink():
        fail("missing safe export data directory")

    entries: list[dict[str, object]] = []
    for path in sorted(data_root.rglob("*.enc")):
        relative = path.relative_to(data_root).as_posix()
        if not relative.isascii() or not any(rule.fullmatch(relative) for rule in ALLOWED):
            fail(f"unapproved export artifact: {relative}")
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
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=True, indent=2) + "\n").encode("ascii")
    write_atomic(root / "manifest-v1.json", manifest_bytes, group.gr_gid)


if __name__ == "__main__":
    main()
