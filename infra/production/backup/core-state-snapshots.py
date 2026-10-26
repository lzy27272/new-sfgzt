#!/usr/bin/env python3
"""File-incremental, encrypted state backups; each index has a direct pack closure.

Only Python stdlib and the already-required OpenSSL CLI are used. Database dumps
remain full dumps. This tool never traverses or prunes a live source directory.
The caller holds hotel-ai-os-backup.lock during create/prune/export/restore.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile

STAMP = r"[0-9]{8}T[0-9]{6}[+-][0-9]{4}"
HEX = re.compile(r"[0-9a-f]{64}")
RAW_NAME = re.compile(r"analytics-raw-([0-9]{4}-[0-9]{2}-[0-9]{2})(?:-([0-9a-f]{64}))?[.]tar[.]gz[.]enc")
KINDS = {"daily": (30, STAMP), "monthly": (12, r"[0-9]{6}"), "yearly": (3, r"[0-9]{4}")}
PACK_LIMIT = 256 * 1024 * 1024
CAPACITY_RESERVE = 4 * 1024 * 1024 * 1024
MAX_INDEX = 128 * 1024 * 1024
REQUIRED = {
    "etc/sifangguan-ota/runtime.env", "var/lib/sifangguan-ota/report-sources.json",
    "var/lib/sifangguan-ota/pms-login-secrets.json", "var/lib/sifangguan-ota/live-report-snapshots.json",
    "var/lib/sifangguan-ota/kpi-monthly-pms-summaries.json",
    "var/lib/hotel-ai-os/backup-core-originals-manifest.json",
}


def canonical(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("ascii")


def regular(path):
    path = Path(path)
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise ValueError("Symlink is not allowed: " + str(path))
    if not stat.S_ISREG(path.stat().st_mode):
        raise ValueError("Not a regular file: " + str(path))
    return path


def sha(path):
    regular(path)
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or path.is_symlink():
        raise ValueError("Unsafe output")
    descriptor, name = tempfile.mkstemp(prefix=".snapshot-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, 0o600)
        os.replace(name, path)
        sync_directory(path.parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def sync_directory(path):
    if os.name != "nt":
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def checksum(path):
    atomic(str(path) + ".sha256", (sha(path) + "  " + Path(path).name + "\n").encode("ascii"))


def checked(path, digest=None, size=None, cache=None):
    path = regular(path)
    fields = regular(str(path) + ".sha256").read_text(encoding="ascii").strip().split()
    info = path.stat()
    identity = (str(path.resolve()), info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    actual = cache.get(identity) if cache is not None else None
    if actual is None:
        actual = sha(path)
        if cache is not None:
            cache[identity] = actual
    if len(fields) != 2 or fields[1].lstrip("*") != path.name or fields[0] != actual:
        raise ValueError("Artifact checksum mismatch: " + str(path))
    if (digest is not None and digest != actual) or (size is not None and size != path.stat().st_size):
        raise ValueError("Closure checksum mismatch: " + str(path))
    return {"sha256": actual, "bytes": path.stat().st_size}


def crypt(source, target, key, decrypt=False):
    regular(key)
    command = ["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000"]
    command += ["-d"] if decrypt else ["-salt"]
    command += ["-pass", "file:" + str(key), "-in", str(source), "-out", str(target)]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    os.chmod(target, 0o600)


def safe_path(value):
    if not isinstance(value, str) or "\\" in value or any(ord(c) < 32 for c in value):
        raise ValueError("Unsafe snapshot path")
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(p in ("..", ".", "") for p in value.split("/")):
        raise ValueError("Unsafe snapshot path")
    if path.parts[0] not in ("etc", "var"):
        raise ValueError("Unexpected state tree")
    if value.startswith("var/lib/sifangguan-ota/analytics-retention/raw/"):
        raise ValueError("RAW_RETENTION_BOUNDARY_VIOLATION")
    return path


def metadata(path):
    value = path.stat()
    attrs = {}
    if hasattr(os, "listxattr"):
        attrs = {name: base64.b64encode(os.getxattr(path, name)).decode("ascii") for name in sorted(os.listxattr(path))}
    return {"mode": stat.S_IMODE(value.st_mode), "uid": value.st_uid, "gid": value.st_gid,
            "mtimeNs": value.st_mtime_ns, "xattrs": attrs}


def inventory(source):
    source = Path(source)
    if source.is_symlink() or not source.is_dir():
        raise ValueError("Unsafe staged state root")
    result = []
    device = source.stat().st_dev
    for path in sorted(source.rglob("*")):
        rel = path.relative_to(source).as_posix()
        safe_path(rel)
        info = path.lstat()
        if info.st_dev != device or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise ValueError("Unsupported link, special file or mounted filesystem: " + rel)
        item = {"path": rel, "kind": "directory" if path.is_dir() else "file", **metadata(path)}
        if item["kind"] == "file":
            item.update(sha256=sha(path), bytes=info.st_size)
            after = path.stat()
            if (after.st_ino, after.st_size, after.st_mtime_ns) != (info.st_ino, info.st_size, info.st_mtime_ns):
                raise ValueError("Staged file changed during capture")
        result.append(item)
    if not REQUIRED.issubset({x["path"] for x in result if x["kind"] == "file"}):
        raise ValueError("Missing required recoverable state")
    return result


def read_index(index, key):
    checked(index)
    with tempfile.TemporaryDirectory(prefix=".read-index-", dir=Path(index).parent) as temporary:
        plain = Path(temporary) / "index.json"
        crypt(index, plain, key, True)
        if plain.stat().st_size > MAX_INDEX:
            raise ValueError("Oversized snapshot index")
        result = json.loads(plain.read_text(encoding="ascii"))
    if result.get("kind") != "SFG_STATE_SNAPSHOT_V2" or not re.fullmatch(STAMP, result.get("snapshotId", "")):
        raise ValueError("Unsupported snapshot index")
    db = result.get("database", {})
    if not HEX.fullmatch(str(db.get("sha256", ""))) or not isinstance(db.get("bytes"), int) or db["bytes"] <= 0:
        raise ValueError("Invalid database pairing")
    packs = result.get("packs", {})
    paths = set()
    used = set()
    for row in result.get("files", []):
        safe_path(row["path"])
        if row["path"] in paths or row["kind"] not in ("file", "directory"):
            raise ValueError("Duplicate or invalid snapshot entry")
        paths.add(row["path"])
        if row["kind"] == "file":
            if not HEX.fullmatch(row["sha256"]) or not HEX.fullmatch(row["pack"]) or row["pack"] not in packs or row["bytes"] < 0:
                raise ValueError("Invalid pack reference")
            used.add(row["pack"])
    if used != set(packs) or not REQUIRED.issubset(paths):
        raise ValueError("Incomplete or extraneous pack closure")
    for digest, item in packs.items():
        if not HEX.fullmatch(digest) or item.get("sha256") != digest or item.get("bytes", 0) <= 0:
            raise ValueError("Invalid pack digest")
    return result


def pack_path(root, digest):
    if not HEX.fullmatch(digest):
        raise ValueError("Invalid object name")
    return Path(root) / "objects" / (digest + ".pack.enc")


def validate_closure(root, index, key, db=None, verify_payload=False, cache=None):
    value = read_index(index, key)
    if db is not None:
        checked(db, value["database"]["sha256"], value["database"]["bytes"])
    for digest, item in value["packs"].items():
        path = pack_path(root, digest)
        checked(path, digest, item["bytes"], cache)
        if verify_payload:
            verify_pack(path, key, {x["sha256"]: x["bytes"] for x in value["files"] if x.get("pack") == digest})
    return value


def verify_pack(path, key, expected, output=None):
    with tempfile.TemporaryDirectory(prefix=".read-pack-", dir=output.parent if output else Path(path).parent) as temporary:
        plain = Path(temporary) / "pack.tar.gz"
        crypt(path, plain, key, True)
        seen = set()
        with tarfile.open(plain, "r:gz") as archive:
            for member in archive:
                if not member.isfile() or not HEX.fullmatch(member.name) or member.name in seen:
                    raise ValueError("Unsafe or duplicate pack entry")
                seen.add(member.name)
                content = archive.extractfile(member)
                digest = hashlib.sha256()
                size = 0
                sink = (output / member.name).open("wb") if output and member.name in expected else None
                try:
                    for chunk in iter(lambda: content.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                        if sink:
                            sink.write(chunk)
                finally:
                    if sink:
                        sink.close()
                if digest.hexdigest() != member.name or (member.name in expected and expected[member.name] != size):
                    raise ValueError("Original SHA256 mismatch in pack")
        if not set(expected).issubset(seen):
            raise ValueError("Missing required pack content")


def indices(root):
    return sorted(path for tier in KINDS for path in (Path(root) / tier).glob("*.snapshot.json.enc"))


def create(source, root, index, snapshot, key, db):
    root, index = Path(root), Path(index)
    if not re.fullmatch(STAMP, snapshot) or index.exists():
        raise ValueError("Invalid or already existing snapshot")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    objects = root / "objects"
    objects.mkdir(mode=0o700, exist_ok=True)
    if objects.is_symlink():
        raise ValueError("Unsafe object directory")
    files = inventory(source)
    catalog, packs = {}, {}
    # Rebuild the dedup catalogue from real retained encrypted indexes, never
    # trust a mutable hash cache or reference a pruned parent snapshot.
    verified = {}  # Per command only; never persist a trusted hash cache.
    for prior in indices(root):
        value = validate_closure(root, prior, key, cache=verified)
        packs.update(value["packs"])
        for row in value["files"]:
            if row["kind"] == "file":
                catalog.setdefault(row["sha256"], row["pack"])
    pending = {}
    for row in files:
        if row["kind"] == "file" and row["sha256"] not in catalog:
            pending.setdefault(row["sha256"], row)
    with tempfile.TemporaryDirectory(prefix=".create-", dir=root) as temporary:
        temporary = Path(temporary)
        batch, size = [], 0
        def flush():
            nonlocal batch, size
            if not batch:
                return
            plain, encrypted = temporary / "pack.tar.gz", temporary / "pack.enc"
            with tarfile.open(plain, "w:gz", format=tarfile.PAX_FORMAT) as archive:
                for row in batch:
                    path = Path(source) / row["path"]
                    if sha(path) != row["sha256"]:
                        raise ValueError("Source changed before packing")
                    info = tarfile.TarInfo(row["sha256"])
                    info.size = row["bytes"]
                    info.mode = 0o600
                    with path.open("rb") as stream:
                        archive.addfile(info, stream)
            crypt(plain, encrypted, key)
            digest = sha(encrypted)
            verify_pack(encrypted, key, {row["sha256"]: row["bytes"] for row in batch})
            target = pack_path(root, digest)
            if target.exists():
                raise ValueError("Immutable pack collision")
            os.replace(encrypted, target)
            checksum(target)
            packs[digest] = {"sha256": digest, "bytes": target.stat().st_size}
            for row in batch:
                catalog[row["sha256"]] = digest
            batch, size = [], 0
        for row in pending.values():
            if batch and size + row["bytes"] > PACK_LIMIT:
                flush()
            batch.append(row)
            size += row["bytes"]
        flush()
        for row in files:
            if row["kind"] == "file":
                row["pack"] = catalog[row["sha256"]]
        used = {row["pack"] for row in files if row["kind"] == "file"}
        value = {"kind": "SFG_STATE_SNAPSHOT_V2", "schemaVersion": 2, "snapshotId": snapshot,
                 "database": checked(db), "files": files, "packs": {x: packs[x] for x in sorted(used)}}
        plain, encrypted = temporary / "index.json", temporary / "index.enc"
        atomic(plain, canonical(value))
        crypt(plain, encrypted, key)
        index.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Commit sidecar first. An interrupted pre-commit leaves no visible
        # index referencing a half-published closure (a harmless orphan sidecar).
        atomic(str(index) + ".sha256", (sha(encrypted) + "  " + index.name + "\n").encode("ascii"))
        os.replace(encrypted, index)
        sync_directory(index.parent)
    validate_closure(root, index, key, db, True, verified)
    return {"snapshotId": snapshot, "newPacks": len(set(catalog[x] for x in pending)), "closurePacks": len(used),
            "stateBytes": sum(row["bytes"] for row in files if row["kind"] == "file"),
            "indexSha256": sha(index), "databaseSha256": value["database"]["sha256"]}


def restore(root, index, destination, key, db, snapshot):
    destination = Path(destination)
    if destination.is_symlink() or not destination.is_dir() or any(destination.iterdir()):
        raise ValueError("Restore requires an empty isolated directory")
    value = validate_closure(root, index, key, db)
    if value["snapshotId"] != snapshot:
        raise ValueError("Wrong database/state snapshot pairing")
    with tempfile.TemporaryDirectory(prefix=".restore-blobs-", dir=destination.parent) as temporary:
        blobs = Path(temporary)
        for row in value["files"]:
            if row["kind"] == "directory":
                (destination / safe_path(row["path"])).mkdir(mode=0o700, parents=True, exist_ok=True)
        for digest in value["packs"]:
            rows = [x for x in value["files"] if x.get("pack") == digest]
            expected = {x["sha256"]: x["bytes"] for x in rows}
            verify_pack(pack_path(root, digest), key, expected, blobs)
            for row in rows:
                target = destination / safe_path(row["path"])
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                shutil.copyfile(blobs / row["sha256"], target)
                if target.stat().st_size != row["bytes"] or sha(target) != row["sha256"]:
                    raise ValueError("Restored original SHA256 mismatch")
            # Only this pack's temporary blobs are needed at once, not another
            # full plaintext copy of the entire historical attachment tree.
            for digest in expected:
                (blobs / digest).unlink()
        for row in sorted(value["files"], key=lambda x: len(PurePosixPath(x["path"]).parts), reverse=True):
            target = destination / row["path"]
            if os.name != "nt":
                os.chown(target, row["uid"], row["gid"])
            os.chmod(target, row["mode"])
            if row["xattrs"] and not hasattr(os, "setxattr"):
                raise ValueError("This restore host cannot preserve Linux xattrs/ACLs")
            for name, encoded in row["xattrs"].items():
                os.setxattr(target, name, base64.b64decode(encoded, validate=True))
            os.utime(target, ns=(row["mtimeNs"], row["mtimeNs"]))
    return {"restore": "STATE_CONTENT_PASS", "snapshotId": snapshot, "indexSha256": sha(index),
            "database": value["database"], "packs": value["packs"], "files": len(value["files"]),
            "metadataRestored": os.name != "nt"}


def points(state, db_root, key, cache=None):
    if cache is None:
        cache = {}
    result = []
    for tier, (_, token) in KINDS.items():
        directory = Path(state) / tier
        for path in sorted(directory.glob("*.enc")):
            match = re.fullmatch(r"sifangguan-ota-state-" + tier + "-(" + token + r")[.](tar[.]gz|snapshot[.]json)[.]enc", path.name)
            if not match:
                raise ValueError("Unmanaged state artifact")
            pair = Path(db_root) / tier / ("hotel_ai_os-" + tier + "-" + match[1] + ".dump.enc")
            checked(path)
            database = checked(pair)
            value = validate_closure(state, path, key, pair, cache=cache) if match[2] == "snapshot.json" else None
            if value and value["snapshotId"][:len(match[1])] != match[1]:
                raise ValueError("State filename does not match frozen snapshot pairing")
            result.append({"path": path, "db": pair, "tier": tier, "key": match[1], "index": value,
                           "database": database})
    return result


def promote(state, db_root, key, snapshot):
    """First point of month/year, with recovery of a half-finished tier copy."""
    state, db_root = Path(state), Path(db_root)
    daily = state / "daily" / ("sifangguan-ota-state-daily-" + snapshot + ".snapshot.json.enc")
    daily_db = db_root / "daily" / ("hotel_ai_os-daily-" + snapshot + ".dump.enc")
    validate_closure(state, daily, key, daily_db)
    def copy_once(source, target):
        if target.exists():
            return
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        temporary = target.with_name("." + target.name + ".promote")
        if os.name != "nt":
            subprocess.run(["cp", "--reflink=auto", "--preserve=mode,timestamps", "--", str(source), str(temporary)], check=True)
        else:
            shutil.copy2(source, temporary)
        os.chmod(temporary, 0o600)
        with temporary.open("r+b") as stream:
            os.fsync(stream.fileno())
        if sha(temporary) != sha(source):
            raise ValueError("Tier copy failed verification")
        atomic(str(target) + ".sha256", (sha(temporary) + "  " + target.name + "\n").encode("ascii"))
        os.replace(temporary, target)
        sync_directory(target.parent)
    for tier, token in (("monthly", snapshot[:6]), ("yearly", snapshot[:4])):
        index = state / tier / ("sifangguan-ota-state-" + tier + "-" + token + ".snapshot.json.enc")
        old = state / tier / ("sifangguan-ota-state-" + tier + "-" + token + ".tar.gz.enc")
        database = db_root / tier / ("hotel_ai_os-" + tier + "-" + token + ".dump.enc")
        if old.exists():
            checked(old)
            checked(database)
            continue
        if index.exists():
            value = read_index(index, key)
            if not database.exists():
                candidates = [p for p in db_root.rglob("*.dump.enc") if sha(p) == value["database"]["sha256"]]
                if not candidates:
                    raise ValueError("Half-promoted index has no exact database pair; historical data retained")
                copy_once(candidates[0], database)
            validate_closure(state, index, key, database)
        elif database.exists():
            digest = checked(database)["sha256"]
            candidates = [p for p in indices(state) if read_index(p, key)["database"]["sha256"] == digest]
            if not candidates:
                raise ValueError("Half-promoted database has no exact state pair; historical data retained")
            copy_once(candidates[0], index)
            validate_closure(state, index, key, database)
        else:
            copy_once(daily, index)
            copy_once(daily_db, database)
            validate_closure(state, index, key, database)
    return {"promotion": "PAIRED"}


def prune(state, db_root, key):
    # Validate EVERYTHING before the first unlink. Corrupt historical roots are
    # not silently dropped to obtain a superficially successful backup run.
    verified = {}
    all_points = points(state, db_root, key, verified)
    if not all_points:
        raise ValueError("No paired recovery roots; refusing backup garbage collection")
    all_databases = {tier: sorted((Path(db_root) / tier).glob("hotel_ai_os-*.dump.enc"), reverse=True) for tier in KINDS}
    for databases in all_databases.values():
        for path in databases:
            checked(path)
    keep = []
    for tier, (count, _) in KINDS.items():
        keys = sorted({x["key"] for x in all_points if x["tier"] == tier}, reverse=True)[:count]
        keep.extend(x for x in all_points if x["tier"] == tier and x["key"] in keys)
    retained = {str(x["path"]) for x in keep}
    reachable = {digest for x in keep if x["index"] for digest in x["index"]["packs"]}
    for path in (Path(state) / "objects").glob("*"):
        if path.name.endswith(".sha256"):
            continue
        if not re.fullmatch(r"[0-9a-f]{64}[.]pack[.]enc", path.name):
            raise ValueError("Unmanaged object blocks pruning")
        # Unreferenced packs may be left between atomic pack/sidecar commits.
        # Their name is the expected ciphertext hash; they are not roots. A
        # missing sidecar cannot strand an otherwise safe retry forever.
        if path.name[:64] not in reachable and not Path(str(path) + ".sha256").exists():
            if sha(path) != path.name[:64]:
                raise ValueError("Corrupt orphan object blocks pruning")
        else:
            checked(path, path.name[:64], cache=verified)
    removed = []
    # A crash may leave extra files but cannot delete a retained dependency.
    for point in all_points:
        if str(point["path"]) not in retained:
            for path in (point["path"], Path(str(point["path"]) + ".sha256")):
                path.unlink()
                removed.append(str(path))
    retained_db = {str(x["db"]) for x in keep}
    for tier, (count, _) in KINDS.items():
        dbs = all_databases[tier]
        # Preserve unpaired release/legacy DB dumps under the original count
        # rule, but never delete an explicitly retained state pair.
        for path in dbs[count:]:
            if str(path) not in retained_db:
                checked(path)
                path.unlink()
                Path(str(path) + ".sha256").unlink()
                removed.append(str(path))
    for path in (Path(state) / "objects").glob("*.pack.enc"):
        if path.name[:64] not in reachable:
            path.unlink()
            Path(str(path) + ".sha256").unlink(missing_ok=True)
            removed.append(str(path))
    return {"retainedPoints": len(keep), "reachablePacks": len(reachable), "removed": len(removed)}


def export_closure(state, db_root, key, output, raw_root):
    recovery = []
    actual_points = points(state, db_root, key)
    artifacts = set()
    for point in actual_points:
        artifacts.add("sifangguan-ota/" + point["tier"] + "/" + point["path"].name)
        value = point["index"]
        if not value:
            continue
        recovery.append({"statePath": "sifangguan-ota/" + point["tier"] + "/" + point["path"].name,
                         "stateSha256": sha(point["path"]), "snapshotId": value["snapshotId"],
                         "databasePath": "hotel-ai-os/" + point["tier"] + "/" + point["db"].name,
                         "databaseSha256": value["database"]["sha256"],
                         "objects": ["sifangguan-ota/objects/" + digest + ".pack.enc" for digest in sorted(value["packs"])]})
        artifacts.update(recovery[-1]["objects"])
    for tier in KINDS:
        for path in (Path(db_root) / tier).glob("*.dump.enc"):
            checked(path)
            artifacts.add("hotel-ai-os/" + tier + "/" + path.name)
    for path in Path(raw_root).glob("analytics-raw-*.tar.gz.enc"):
        checked(path)
        artifacts.add("analytics-raw/daily/" + path.name)
    atomic(output, canonical({"kind": "SFG_RECOVERY_CLOSURE_V2", "recoveryPoints": recovery,
                              "artifactPaths": sorted(artifacts)}))
    return {"recoveryPoints": len(recovery)}


def prepare_offsite(data_root, manifest_path=None, receipt_path=None, bundle_path=None):
    """Validate an accepted device copy BEFORE recreating checksum sidecars.

    Offline recovery deliberately allows an old accepted manifest. Its receipt
    must match, and every byte must match that manifest, not a newly guessed
    digest. No backup decryption key is needed or installed on the device.
    """
    data_root = Path(data_root)
    canonical_bundle = data_root.parent / "control/accepted-bundle.json"
    legacy_sibling = Path(manifest_path).with_name("accepted-bundle.json") if manifest_path is not None else canonical_bundle
    candidate = Path(bundle_path) if bundle_path is not None else (
        canonical_bundle if canonical_bundle.exists() or canonical_bundle.is_symlink() else legacy_sibling)
    if bundle_path is not None or candidate.exists() or candidate.is_symlink():
        if regular(candidate).stat().st_size > 2 * MAX_INDEX:
            raise ValueError("Oversized accepted bundle")
        bundle = json.loads(candidate.read_text(encoding="utf-8-sig"))
        if bundle.get("kind") != "SFG_ACCEPTED_BACKUP_BUNDLE_V1" or bundle.get("schemaVersion") != 1:
            raise ValueError("Invalid authoritative accepted bundle")
        manifest, receipt = bundle.get("manifest", {}), bundle.get("receipt", {})
        authority = "ATOMIC_BUNDLE"
        # An existing broken bundle must never fall back to torn legacy files.
    else:
        if manifest_path is None or receipt_path is None:
            raise ValueError("Missing accepted bundle or legacy manifest/receipt")
        if regular(manifest_path).stat().st_size > MAX_INDEX or regular(receipt_path).stat().st_size > 16384:
            raise ValueError("Oversized accepted manifest/receipt")
        manifest = json.loads(regular(manifest_path).read_text(encoding="utf-8-sig"))
        receipt = json.loads(regular(receipt_path).read_text(encoding="utf-8-sig"))
        authority = "LEGACY_PAIR"
    if (manifest.get("kind") != "SFG_BACKUP_MANIFEST_V1" or manifest.get("schemaVersion") != 1
        or not HEX.fullmatch(str(manifest.get("manifestId", "")))
        or receipt.get("kind") != "SFG_BACKUP_RECEIPT_TOKEN_V1"
        or receipt.get("manifestId") != manifest["manifestId"]):
        raise ValueError("Missing accepted manifest/receipt pair")
    spec = importlib.util.spec_from_file_location("backup_manifest_rules", Path(__file__).with_name("publish-backup-manifest.py"))
    publisher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publisher)
    entries = manifest.get("entries", [])
    if not 2 <= len(entries) <= 200000:
        raise ValueError("Invalid offline entry count")
    seen = set()
    for entry in entries:
        relative = entry.get("path", "")
        if not any(pattern.fullmatch(relative) for pattern in publisher.ALLOWED) or relative in seen:
            raise ValueError("Unsafe or duplicate offline artifact")
        seen.add(relative)
        parts = relative.split("/")
        if entry.get("category") != parts[0] or entry.get("tier") != parts[1] or not HEX.fullmatch(str(entry.get("sha256", ""))):
            raise ValueError("Invalid offline artifact metadata")
        path = regular(data_root / relative)
        if path.stat().st_size != entry.get("bytes") or sha(path) != entry["sha256"]:
            raise ValueError("Offline artifact differs from accepted manifest")
        raw_match = RAW_NAME.fullmatch(path.name) if entry["category"] == "analytics-raw" else None
        if raw_match and raw_match[2] and raw_match[2] != entry["sha256"]:
            raise ValueError("Immutable raw revision name differs from ciphertext")
    publisher.validate_recovery_points(entries, manifest)
    if not {"hotel-ai-os", "sifangguan-ota"}.issubset({entry["category"] for entry in entries}):
        raise ValueError("Offline accepted manifest lacks required database/state categories")
    # All bytes and all closure links passed, so these recorded values are safe
    # to materialize. A failure above never certifies a corrupt local file.
    for entry in entries:
        path = data_root / entry["path"]
        atomic(str(path) + ".sha256", (entry["sha256"] + "  " + path.name + "\n").encode("ascii"))
    return {"preparation": "OFFSITE_MANIFEST_VERIFIED", "manifestId": manifest["manifestId"], "artifacts": len(entries), "authority": authority}


def gzip_upper_bound(size):
    # zlib compressBound plus room for gzip framing. This budgets expansion,
    # never an assumed compression ratio (all payload may be incompressible).
    return size + (size >> 12) + (size >> 14) + (size >> 25) + 64


def numeric_lengths(path):
    """Strict rsync operation/length records; legacy numeric fixtures remain valid.

    Including %o makes rsync format deletions through the same machine log
    instead of emitting '*deleting <filename>'. Deleted bytes are never credit.
    """
    values = []
    with regular(path).open(encoding="ascii") as source:
        for line in source:
            value = line.rstrip("\r\n")
            operation = "send"
            if not re.fullmatch(r"[0-9]{1,19}", value):
                record = re.fullmatch(r"(send|recv|del[.]) ([0-9]{1,19})", value)
                if record is None:
                    raise ValueError("Capacity measurement requires strict rsync operation and length records")
                operation, value = record.groups()
            number = int(value)
            if number > 2**63 - 1 or len(values) >= 2_000_000:
                raise ValueError("Capacity measurement exceeds supported limits")
            if operation == "del.":
                continue
            values.append(number)
    return values


def capacity_measurements(state_lengths, core_lengths, raw_lengths, completed_lengths, pending_lengths, completed_archives):
    if not isinstance(completed_archives, int) or not 0 <= completed_archives <= 100000:
        raise ValueError("Invalid completed raw archive count")
    state = numeric_lengths(state_lengths) + numeric_lengths(core_lengths)
    raw, completed, pending = (numeric_lengths(path) for path in (raw_lengths, completed_lengths, pending_lengths))
    if not state or sum(state) < 1 or sum(completed) > sum(raw):
        raise ValueError("Invalid fresh source capacity measurement")
    if completed and not completed_archives:
        raise ValueError("Completed raw data has no date archive")
    # Treat every selected entry as new content. rsync can include directories
    # in %l; counting them as files only increases this bound. No dedup credit.
    # Generated original-reference manifest/runtime config get a full 128MiB
    # allowance even though they do not yet exist in the source dry run.
    sizes = state + [MAX_INDEX]
    payload = sum(sizes)
    # create() sorts paths, whereas rsync emits another traversal order. Use
    # order-independent next-fit bounds rather than simulate its batch order:
    # at most 2*ceil(payload/limit)+1 batches (and never more than entries).
    pack_count = min(len(sizes), 2 * ((payload + PACK_LIMIT - 1) // PACK_LIMIT) + 1)
    # Hash member names are 64 ASCII chars; 4KiB per entry also covers PAX
    # large-size headers, separately including each member's tar block padding.
    overhead = sum(((size + 511) // 512) * 512 - size + 4096 for size in sizes)
    total_tar = payload + overhead + 11264 * pack_count
    state_packs = gzip_upper_bound(total_tar) + 128 * pack_count
    largest_payload = max(PACK_LIMIT, max(sizes)) + overhead
    largest_tar = ((largest_payload + 1024 + 10239) // 10240) * 10240
    largest_pack = gzip_upper_bound(largest_tar) + 64
    # Raw tar members retain paths, so allow 16KiB per entry for numeric-owner
    # tar/PAX metadata and path headers, plus alignment/trailers per date.
    raw_payload = sum(completed) + 16384 * len(completed) + 11264 * completed_archives
    raw_archive = gzip_upper_bound(raw_payload) + 128 * completed_archives if completed_archives else 0
    return {"kind": "SFG_BACKUP_CAPACITY_MEASUREMENT_V1", "stateBytes": sum(state), "stateEntries": len(state),
            "stateMaxFileBytes": max(state), "newStatePackBytes": state_packs, "largestStatePackBytes": largest_pack,
            "rawBytes": sum(raw), "rawCompletedBytes": sum(completed), "rawArchiveBytes": raw_archive,
            "rawCompletedEntries": len(completed), "rawCompletedArchives": completed_archives,
            "stagePendingBytes": sum(pending), "stagePendingEntries": len(pending)}


def check_capacity(state_root, database_root, stage_root, export_root, state_bytes, database_bytes, raw_root=None, raw_bytes=0, measurements=None):
    """Conservative first-run bound; do not depend on compression/dedup ratios.

    Source staging + full new state packs + their export copy; five DB local
    copies (plain/check/daily/month/year) and three exported encrypted copies.
    Space shared by roles is summed, not checked independently against the same
    free bytes. Existing retained history is already accounted in current free.
    """
    if state_bytes < 1 or database_bytes < 1 or raw_bytes < 0:
        raise ValueError("Cannot establish actual state/database backup size")
    if measurements is None:
        requirements = [(state_root, state_bytes + 2 * max(PACK_LIMIT, state_bytes)),
                        (stage_root, state_bytes + raw_bytes), (database_root, 5 * database_bytes),
                        (export_root, state_bytes + 3 * database_bytes + raw_bytes)]
        raw_required = 2 * raw_bytes
    else:
        m = measurements
        fields = ("stateBytes", "stateEntries", "stateMaxFileBytes", "newStatePackBytes", "largestStatePackBytes",
                  "rawBytes", "rawCompletedBytes", "rawArchiveBytes", "rawCompletedEntries", "rawCompletedArchives",
                  "stagePendingBytes", "stagePendingEntries")
        if (m.get("kind") != "SFG_BACKUP_CAPACITY_MEASUREMENT_V1" or
            any(type(m.get(key)) is not int or m[key] < 0 for key in fields) or
            m["stateBytes"] != state_bytes or m["rawBytes"] != raw_bytes or
            not 0 < m["stateMaxFileBytes"] <= state_bytes or m["rawCompletedBytes"] > raw_bytes or
            m["newStatePackBytes"] < state_bytes or m["largestStatePackBytes"] <= 0 or
            m["rawArchiveBytes"] < m["rawCompletedBytes"]):
            raise ValueError("Invalid fresh capacity measurements")
        state_pack, temporary_pack = m["newStatePackBytes"], m["largestStatePackBytes"]
        raw_archive = m["rawArchiveBytes"]
        # Persistent all-new packs plus the largest simultaneous plaintext /
        # encrypted verification pack. Index reserve covers daily/month/year
        # copies and readback. Existing stage occupies current free already;
        # rsync pending includes replacement temp files, never deleted space.
        requirements = [(state_root, state_pack + 2 * temporary_pack + 5 * MAX_INDEX),
                        (stage_root, m["stagePendingBytes"] + MAX_INDEX),
                        (database_root, 5 * database_bytes),
                        (export_root, state_pack + 3 * MAX_INDEX + 3 * database_bytes + raw_archive)]
        raw_required = 2 * raw_archive
    if raw_required:
        if raw_root is None:
            raise ValueError("Missing raw backup filesystem for capacity check")
        requirements.append((raw_root, raw_required))
    devices = {}
    for path, needed in requirements:
        path = Path(path)
        if path.is_symlink() or not path.is_dir():
            raise ValueError("Unsafe capacity-check directory")
        device = path.stat().st_dev
        item = devices.setdefault(device, {"path": path, "required": CAPACITY_RESERVE})
        item["required"] += needed
    result = []
    for item in devices.values():
        free = shutil.disk_usage(item["path"]).free
        if free < item["required"]:
            raise ValueError("BACKUP_CAPACITY_INSUFFICIENT before writer stop/prune: free=" + str(free) + " required=" + str(item["required"]))
        result.append({"path": str(item["path"]), "freeBytes": free, "requiredBytes": item["required"]})
    return {"capacity": "PASS", "filesystems": result, "compressionAssumed": False,
            "measurement": measurements, "reserveBytesPerFilesystem": CAPACITY_RESERVE}


def prune_raw_dates(root, keep=90):
    root = Path(root)
    if root.is_symlink() or not root.is_dir() or keep < 1:
        raise ValueError("Unsafe raw retention root")
    rows = []
    for path in root.glob("*.enc"):
        match = RAW_NAME.fullmatch(path.name)
        if not match:
            raise ValueError("Unmanaged raw artifact blocks retention")
        dt.date.fromisoformat(match[1])
        record = checked(path)
        if match[2] and match[2] != record["sha256"]:
            raise ValueError("Immutable raw revision digest mismatch")
        rows.append((path, match[1]))
    dates = sorted({day for _, day in rows}, reverse=True)
    retained = set(dates[:keep])
    removed = 0
    for path, day in rows:
        if day not in retained:
            path.unlink()
            Path(str(path) + ".sha256").unlink()
            Path(str(path) + ".source.sha256").unlink(missing_ok=True)
            removed += 1
    for day in dates[keep:]:
        (root / (".analytics-raw-" + day + ".source.sha256")).unlink(missing_ok=True)
    return {"retainedSourceDates": len(retained), "retainedArtifacts": len(rows)-removed, "removed": removed}


def commit_raw_revision(root, temporary, day, fingerprint):
    root, temporary = Path(root), Path(temporary)
    dt.date.fromisoformat(day)
    if root.is_symlink() or not root.is_dir() or temporary.parent.resolve() != root.resolve() or not HEX.fullmatch(fingerprint):
        raise ValueError("Unsafe raw revision publication")
    if not re.fullmatch(r"[.]analytics-raw-" + re.escape(day) + r"[.][0-9]+[.]tar[.]gz[.]enc", temporary.name):
        raise ValueError("Unexpected raw revision temporary file")
    digest = sha(temporary)
    target = root / ("analytics-raw-" + day + "-" + digest + ".tar.gz.enc")
    if target.exists():
        if sha(target) != digest:
            raise ValueError("Immutable raw revision collision")
        temporary.unlink()
    else:
        os.chmod(temporary, 0o600)
        with temporary.open("r+b") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        sync_directory(root)
    checksum(target)
    atomic(root / (".analytics-raw-" + day + ".source.sha256"), (fingerprint + " " + target.name + "\n").encode("ascii"))
    return {"rawArchive": target.name, "sha256": digest, "immutable": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    raw_retention = commands.add_parser("prune-raw")
    raw_retention.add_argument("--raw-root", type=Path, required=True)
    raw_retention.add_argument("--keep", type=int, default=90)
    raw_commit = commands.add_parser("commit-raw")
    raw_commit.add_argument("--raw-root", type=Path, required=True)
    raw_commit.add_argument("--source-temporary", type=Path, required=True)
    raw_commit.add_argument("--day", required=True)
    raw_commit.add_argument("--fingerprint", required=True)
    capacity = commands.add_parser("capacity")
    for name in ("state-root", "database-root", "stage-root", "export-root"):
        capacity.add_argument("--" + name, type=Path, required=True)
    capacity.add_argument("--state-bytes", type=int, required=True)
    capacity.add_argument("--database-bytes", type=int, required=True)
    capacity.add_argument("--raw-backup-root", type=Path)
    capacity.add_argument("--raw-bytes", type=int, default=0)
    capacity.add_argument("--measurements", type=Path)
    measure = commands.add_parser("measure")
    for name in ("state-lengths", "core-lengths", "raw-lengths", "completed-lengths", "pending-lengths"):
        measure.add_argument("--" + name, type=Path, required=True)
    measure.add_argument("--completed-archives", type=int, required=True)
    offline = commands.add_parser("prepare-offsite")
    offline.add_argument("--data-root", type=Path, required=True)
    offline.add_argument("--manifest", type=Path)
    offline.add_argument("--receipt", type=Path)
    offline.add_argument("--bundle", type=Path)
    for action in ("create", "restore", "validate", "prune", "promote", "export-closure"):
        p = commands.add_parser(action)
        p.add_argument("--state-root", type=Path, required=True)
        p.add_argument("--key", type=Path, required=True)
        if action in ("create", "restore", "validate"):
            p.add_argument("--index", type=Path, required=True)
            p.add_argument("--database", type=Path, required=True)
        else:
            p.add_argument("--database-root", type=Path, required=True)
        if action in ("create", "restore", "promote"):
            p.add_argument("--snapshot", required=True)
        if action == "create":
            p.add_argument("--source", type=Path, required=True)
        if action == "restore":
            p.add_argument("--destination", type=Path, required=True)
        if action == "export-closure":
            p.add_argument("--output", type=Path, required=True)
            p.add_argument("--raw-root", type=Path, required=True)
    a = parser.parse_args()
    if a.action == "commit-raw":
        result = commit_raw_revision(a.raw_root, a.source_temporary, a.day, a.fingerprint)
    elif a.action == "prune-raw":
        result = prune_raw_dates(a.raw_root, a.keep)
    elif a.action == "capacity":
        measurement = json.loads(regular(a.measurements).read_text(encoding="ascii")) if a.measurements else None
        result = check_capacity(a.state_root, a.database_root, a.stage_root, a.export_root, a.state_bytes, a.database_bytes, a.raw_backup_root, a.raw_bytes, measurement)
    elif a.action == "measure":
        result = capacity_measurements(a.state_lengths, a.core_lengths, a.raw_lengths, a.completed_lengths, a.pending_lengths, a.completed_archives)
    elif a.action == "prepare-offsite":
        result = prepare_offsite(a.data_root, a.manifest, a.receipt, a.bundle)
    elif a.action == "create":
        result = create(a.source, a.state_root, a.index, a.snapshot, a.key, a.database)
    elif a.action == "restore":
        result = restore(a.state_root, a.index, a.destination, a.key, a.database, a.snapshot)
    elif a.action == "validate":
        value = validate_closure(a.state_root, a.index, a.key, a.database, True)
        result = {"snapshotId": value["snapshotId"], "closure": "PASS", "packs": len(value["packs"])}
    elif a.action == "prune":
        result = prune(a.state_root, a.database_root, a.key)
    elif a.action == "promote":
        result = promote(a.state_root, a.database_root, a.key, a.snapshot)
    else:
        result = export_closure(a.state_root, a.database_root, a.key, a.output, a.raw_root)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
