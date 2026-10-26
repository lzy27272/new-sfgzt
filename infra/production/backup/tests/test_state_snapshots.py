"""Real local encryption/dedup/restore tests. Not a production PG rehearsal."""
import copy
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import socket
import shutil
import subprocess
import struct
import tempfile
import tarfile
import types
import unittest
from unittest import mock

BASE = Path(__file__).resolve().parents[1]
def module(name, file):
    spec = importlib.util.spec_from_file_location(name, BASE / file)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result
S = module("snapshots", "core-state-snapshots.py")
P = module("publisher", "publish-backup-manifest.py")
O = module("originals", "core-originals-manifest.py")


@unittest.skipUnless(shutil.which("openssl"), "Existing OpenSSL CLI is required; no downloads/install")
class StateSnapshotsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source, self.state, self.db = (self.base / x for x in ("source", "state", "db"))
        self.source.mkdir()
        for tier in S.KINDS:
            (self.state / tier).mkdir(parents=True)
            (self.db / tier).mkdir(parents=True)
        self.key = self.base / "key"
        self.key.write_bytes(os.urandom(32).hex().encode())
        self.key.chmod(0o600)
        for name in S.REQUIRED:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((name + " actual recoverable bytes").encode())
        self.photo = self.source / "var/lib/hotel-ai-os/attachments/finance/original.pdf"
        self.photo.parent.mkdir(parents=True)
        self.photo.write_bytes(b"%PDF-original-" * 1200)
        self.photo.chmod(0o640)

    def capture(self, stamp="20261025T010000+0800"):
        db = self.db / "daily" / ("hotel_ai_os-daily-" + stamp + ".dump.enc")
        db.write_bytes(("fixture database paired with " + stamp).encode())
        S.checksum(db)
        index = self.state / "daily" / ("sifangguan-ota-state-daily-" + stamp + ".snapshot.json.enc")
        result = S.create(self.source, self.state, index, stamp, self.key, db)
        return index, db, result

    def restore(self, index, db, stamp):
        destination = self.base / ("restored-" + str(len(list(self.base.glob("restored-*")))))
        destination.mkdir()
        proof = S.restore(self.state, index, destination, self.key, db, stamp)
        return destination, proof

    def test_identical_content_adds_no_pack_and_changed_file_only_adds_its_bytes(self):
        first, db, a = self.capture()
        self.assertEqual(a["newPacks"], 1)
        second, db2, b = self.capture("20261026T010000+0800")
        self.assertEqual(b["newPacks"], 0)
        self.assertEqual(len(list((self.state / "objects").glob("*.enc"))), 1)
        self.photo.write_bytes(b"%PDF-one-changed-photo")
        third, db3, c = self.capture("20261027T010000+0800")
        self.assertEqual(c["newPacks"], 1)
        self.assertEqual(c["closurePacks"], 2)
        old, proof = self.restore(first, db, "20261025T010000+0800")
        self.assertEqual((old / self.photo.relative_to(self.source)).read_bytes(), b"%PDF-original-" * 1200)
        new, _ = self.restore(third, db3, "20261027T010000+0800")
        self.assertEqual((new / self.photo.relative_to(self.source)).read_bytes(), self.photo.read_bytes())
        self.assertEqual(proof["restore"], "STATE_CONTENT_PASS")
        self.assertEqual(proof["indexSha256"], S.sha(first))

    def test_successful_create_stdout_binds_actual_state_bytes_to_selected_encrypted_archives(self):
        index, database, result = self.capture()
        expected = sum(path.stat().st_size for path in self.source.rglob("*") if path.is_file())
        self.assertEqual(result["snapshotId"], "20261025T010000+0800")
        self.assertEqual(result["stateBytes"], expected)
        self.assertEqual(result["indexSha256"], S.sha(index))
        self.assertEqual(result["databaseSha256"], S.sha(database))
        self.assertNotIn("kind", result)
        frozen = S.read_index(index, self.key)
        self.assertEqual(result["stateBytes"], sum(row["bytes"] for row in frozen["files"] if row["kind"] == "file"))
        # A later/failed backup can shrink mutable staging while the completed
        # archive and its success JSON still describe the original full state.
        self.photo.write_bytes(b"smaller later staging")
        current = sum(path.stat().st_size for path in self.source.rglob("*") if path.is_file())
        self.assertGreater(result["stateBytes"], current)
        self.assertEqual(result["indexSha256"], S.sha(index))
        self.assertEqual(result["databaseSha256"], S.sha(database))

    def test_rename_delete_does_not_erase_previous_snapshot_and_dedups_duplicate_content(self):
        first, db, _ = self.capture()
        moved = self.photo.with_name("renamed.pdf")
        self.photo.rename(moved)
        second, db2, result = self.capture("20261026T010000+0800")
        self.assertEqual(result["newPacks"], 0)
        restored, _ = self.restore(first, db, "20261025T010000+0800")
        self.assertTrue((restored / self.photo.relative_to(self.source)).is_file())
        restored2, _ = self.restore(second, db2, "20261026T010000+0800")
        self.assertFalse((restored2 / self.photo.relative_to(self.source)).exists())
        self.assertTrue((restored2 / moved.relative_to(self.source)).exists())

    def test_missing_corrupt_objects_wrong_key_and_wrong_pair_fail_closed(self):
        index, db, _ = self.capture()
        value = S.read_index(index, self.key)
        obj = S.pack_path(self.state, next(iter(value["packs"])))
        original = obj.read_bytes()
        obj.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            S.validate_closure(self.state, index, self.key, db)
        before = {p.relative_to(self.base).as_posix() for p in self.base.rglob("*") if p.is_file()}
        with self.assertRaises(ValueError):
            S.prune(self.state, self.db, self.key)
        self.assertEqual(before, {p.relative_to(self.base).as_posix() for p in self.base.rglob("*") if p.is_file()})
        obj.write_bytes(original)
        badkey = self.base / "bad-key"; badkey.write_bytes(b"different key")
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            S.validate_closure(self.state, index, badkey, db)
        other = self.base / "other.dump.enc"; other.write_bytes(b"other dump"); S.checksum(other)
        with self.assertRaisesRegex(ValueError, "Closure"):
            self.restore(index, other, "20261025T010000+0800")
        with self.assertRaisesRegex(ValueError, "pairing"):
            self.restore(index, db, "20261026T010000+0800")
        obj.unlink()
        with self.assertRaises(FileNotFoundError):
            S.validate_closure(self.state, index, self.key, db)

    def test_monthly_yearly_are_same_index_and_half_promotion_recovers_exact_pair(self):
        index, db, _ = self.capture()
        S.promote(self.state, self.db, self.key, "20261025T010000+0800")
        monthly = self.state / "monthly/sifangguan-ota-state-monthly-202610.snapshot.json.enc"
        yearly = self.state / "yearly/sifangguan-ota-state-yearly-2026.snapshot.json.enc"
        self.assertEqual(S.sha(index), S.sha(monthly)); self.assertEqual(S.sha(index), S.sha(yearly))
        monthly.unlink(); Path(str(monthly) + ".sha256").unlink()
        # Next day is different, but the month must recover the first day's
        # exact old encrypted index, not silently attach this new database.
        self.photo.write_bytes(b"different day")
        self.capture("20261026T010000+0800")
        S.promote(self.state, self.db, self.key, "20261026T010000+0800")
        self.assertEqual(S.sha(index), S.sha(monthly))
        monthly_db = self.db / "monthly/hotel_ai_os-monthly-202610.dump.enc"
        monthly_db.unlink(); Path(str(monthly_db) + ".sha256").unlink()
        S.promote(self.state, self.db, self.key, "20261026T010000+0800")
        self.assertEqual(S.sha(db), S.sha(monthly_db))

    def seed_point(self, template, snapshot, tier, token):
        value = copy.deepcopy(template); value["snapshotId"] = snapshot
        database = self.db / tier / ("hotel_ai_os-" + tier + "-" + token + ".dump.enc")
        database.write_bytes(("paired " + snapshot).encode()); S.checksum(database)
        value["database"] = S.checked(database)
        index = self.state / tier / ("sifangguan-ota-state-" + tier + "-" + token + ".snapshot.json.enc")
        plain = self.base / "seed.json"; plain.write_bytes(S.canonical(value))
        S.crypt(plain, index, self.key); S.checksum(index)
        return index, database

    def test_30_12_3_retention_shared_closure_is_recoverable_after_old_daily_disappears(self):
        index, _, _ = self.capture()
        template = S.read_index(index, self.key)
        index.unlink(); Path(str(index) + ".sha256").unlink()
        start = dt.date(2025, 12, 15)
        for day in range(36):
            stamp = (start + dt.timedelta(days=day)).strftime("%Y%m%d") + "T010000+0800"
            self.seed_point(template, stamp, "daily", stamp)
        for month in range(1, 14):
            year, m = (2024, month) if month <= 12 else (2025, 1)
            token = f"{year:04d}{m:02d}"
            self.seed_point(template, token + "01T010000+0800", "monthly", token)
        for year in range(2022, 2026):
            self.seed_point(template, str(year) + "0101T010000+0800", "yearly", str(year))
        result = S.prune(self.state, self.db, self.key)
        self.assertEqual(result["retainedPoints"], 45)
        self.assertEqual(len(S.indices(self.state)), 45)
        self.assertEqual(len(list((self.state / "objects").glob("*.enc"))), 1)
        for tier, count in (("daily", 30), ("monthly", 12), ("yearly", 3)):
            points = [x for x in S.points(self.state, self.db, self.key) if x["tier"] == tier]
            self.assertEqual(len(points), count)
            for point in points:
                restored, _ = self.restore(point["path"], point["db"], point["index"]["snapshotId"])
                self.assertEqual((restored / self.photo.relative_to(self.source)).read_bytes(), self.photo.read_bytes())

    def test_unreferenced_pack_gc_and_failed_creation_leave_old_point_restorable(self):
        index, db, _ = self.capture()
        before = set((self.state / "objects").glob("*.enc"))
        self.photo.write_bytes(b"new bytes")
        original_crypt = S.crypt
        def fail_index(source, target, key, decrypt=False):
            if Path(source).name == "index.json" and not decrypt:
                raise RuntimeError("injected before index commit")
            return original_crypt(source, target, key, decrypt)
        with mock.patch.object(S, "crypt", side_effect=fail_index):
            with self.assertRaisesRegex(RuntimeError, "injected"):
                self.capture("20261026T010000+0800")
        self.assertGreater(len(list((self.state / "objects").glob("*.enc"))), len(before))
        S.prune(self.state, self.db, self.key)
        self.assertEqual(before, set((self.state / "objects").glob("*.enc")))
        self.restore(index, db, "20261025T010000+0800")
        self.capture("20261026T010000+0800")  # safe retry using the same actual source

    def test_core_database_reference_proof_still_checks_actual_restored_originals(self):
        originals = self.source / "var/lib/hotel-ai-os/attachments"
        config = {"root": "/var/lib/hotel-ai-os/attachments", "financeRelative": "finance", "businessRelative": "business", "academyRelative": "academy"}
        refs = [{"domain": "FINANCE", "source_table": "finance_invoice_intake", "source_id": "actual-source",
                 "object_key": "original.pdf", "size_bytes": self.photo.stat().st_size, "sha256": S.sha(self.photo)}]
        frozen = self.source / "var/lib/hotel-ai-os/backup-core-originals-manifest.json"
        O.capture(originals, frozen, "20261025T010000+0800", config, refs)
        index, db, _ = self.capture()
        restored, _ = self.restore(index, db, "20261025T010000+0800")
        proof = O.verify(restored / originals.relative_to(self.source), restored / frozen.relative_to(self.source), "20261025T010000+0800", refs)
        self.assertEqual(proof["restore"], "PASS")

    def test_legacy_standalone_is_not_converted_and_remains_paired_in_mixed_retention(self):
        legacy = self.state / "monthly/sifangguan-ota-state-monthly-202610.tar.gz.enc"
        legacy.write_bytes(b"legacy independently encrypted archive preserved"); S.checksum(legacy)
        database = self.db / "monthly/hotel_ai_os-monthly-202610.dump.enc"
        database.write_bytes(b"legacy database"); S.checksum(database)
        self.capture(); S.promote(self.state, self.db, self.key, "20261025T010000+0800")
        S.prune(self.state, self.db, self.key)
        self.assertEqual(legacy.read_bytes(), b"legacy independently encrypted archive preserved")
        self.assertFalse(legacy.with_name(legacy.name.replace("tar.gz", "snapshot.json")).exists())

    def test_actual_legacy_encrypted_tar_roundtrip_remains_readable(self):
        archive = self.base / "legacy.tar.gz"
        with tarfile.open(archive, "w:gz") as stream:
            stream.add(self.source / "etc", arcname="etc")
            stream.add(self.source / "var", arcname="var")
        encrypted = self.base / "legacy.tar.gz.enc"
        S.crypt(archive, encrypted, self.key)
        decrypted = self.base / "decrypted.tar.gz"
        S.crypt(encrypted, decrypted, self.key, True)
        target = self.base / "legacy-restored"; target.mkdir()
        O.extract(decrypted, target)
        self.assertEqual((target / self.photo.relative_to(self.source)).read_bytes(), self.photo.read_bytes())

    def test_path_links_missing_required_and_nonempty_restore_rejected(self):
        index, db, _ = self.capture()
        with self.assertRaises(ValueError):
            S.restore(self.state, index, self.source, self.key, db, "20261025T010000+0800")
        for path in ("../outside", "var/../outside", "/etc/passwd", "other/source", "var/link\\bad"):
            with self.assertRaises(ValueError): S.safe_path(path)
        (self.source / next(iter(S.REQUIRED))).unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.capture("20261026T010000+0800")

    @unittest.skipUnless(hasattr(os, "setxattr") and os.name != "nt", "Linux ACL/xattr contract")
    def test_numeric_metadata_xattrs_and_mtime_are_restored_not_silently_discarded(self):
        try: os.setxattr(self.photo, "user.backup-test", b"actual attribute")
        except OSError: self.skipTest("Fixture filesystem has no xattr support")
        index, db, _ = self.capture()
        restored, proof = self.restore(index, db, "20261025T010000+0800")
        target = restored / self.photo.relative_to(self.source)
        self.assertEqual(os.getxattr(target, "user.backup-test"), b"actual attribute")
        self.assertEqual(target.stat().st_mtime_ns, self.photo.stat().st_mtime_ns)
        self.assertEqual(target.stat().st_mode & 0o777, 0o640)
        self.assertTrue(proof["metadataRestored"])

    @unittest.skipUnless(hasattr(os, "setxattr") and os.name != "nt", "Linux POSIX ACL contract")
    def test_linux_posix_acl_bytes_survive_real_encryption_restore(self):
        uid = os.getuid() + 1
        entries = [(1, 6, 0xffffffff), (2, 4, uid), (4, 4, 0xffffffff), (16, 4, 0xffffffff), (32, 0, 0xffffffff)]
        acl = struct.pack("<I", 2) + b"".join(struct.pack("<HHI", *entry) for entry in entries)
        try: os.setxattr(self.photo, "system.posix_acl_access", acl)
        except OSError: self.skipTest("Fixture filesystem does not support POSIX ACL xattrs")
        index, db, _ = self.capture()
        restored, _ = self.restore(index, db, "20261025T010000+0800")
        self.assertEqual(os.getxattr(restored / self.photo.relative_to(self.source), "system.posix_acl_access"), acl)

    def test_export_closure_rejects_missing_or_unbound_object_and_does_not_expose_source_names(self):
        self.capture(); raw = self.base / "raw"; raw.mkdir()
        output = self.base / "closure.json"
        S.export_closure(self.state, self.db, self.key, output, raw)
        selection = json.loads(output.read_text())
        entries = []
        for relative in selection["artifactPaths"]:
            category, tier, name = relative.split("/")
            path = self.db / tier / name if category == "hotel-ai-os" else self.state / tier / name
            entries.append({"path": relative, "category": category, "tier": tier, **S.checked(path)})
        self.assertEqual(len(P.validate_recovery_points(entries, selection)), 1)
        self.assertNotIn("original.pdf", output.read_text())
        with self.assertRaises(SystemExit):
            P.validate_recovery_points([x for x in entries if x["tier"] != "objects"], selection)
        bad = copy.deepcopy(selection); bad["recoveryPoints"][0]["databaseSha256"] = "0" * 64
        with self.assertRaises(SystemExit): P.validate_recovery_points(entries, bad)

    def test_only_windows_downloaded_artifacts_prepare_and_restore_without_server_sidecars(self):
        self.capture(); raw = self.base / "raw"; raw.mkdir()
        closure = self.base / "closure.json"
        S.export_closure(self.state, self.db, self.key, closure, raw)
        selection = json.loads(closure.read_text())
        device = self.base / "device"; data = device / "data"
        entries = []
        for relative in selection["artifactPaths"]:
            category, tier, name = relative.split("/")
            source = self.db / tier / name if category == "hotel-ai-os" else self.state / tier / name
            target = data / relative; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)  # Exactly what Windows pulls: .enc only.
            entries.append({"path": relative, "category": category, "tier": tier, **S.checked(source)})
        manifest = device / "accepted-manifest.json"; receipt = device / "last-receipt.json"
        payload = {"kind": "SFG_BACKUP_MANIFEST_V1", "schemaVersion": 1, "manifestId": "a" * 64,
                   "entries": entries, "recoveryPoints": selection["recoveryPoints"]}
        manifest.write_text(json.dumps(payload)); receipt.write_text(json.dumps({"kind": "SFG_BACKUP_RECEIPT_TOKEN_V1", "manifestId": "a" * 64}))
        self.assertEqual(len(list(data.rglob("*.sha256"))), 0)
        one = data / entries[0]["path"]; original = one.read_bytes(); one.write_bytes(b"bad download")
        with self.assertRaisesRegex(ValueError, "accepted manifest"):
            S.prepare_offsite(data, manifest, receipt)
        self.assertEqual(len(list(data.rglob("*.sha256"))), 0)
        one.write_bytes(original)
        receipt.write_text(json.dumps({"kind": "SFG_BACKUP_RECEIPT_TOKEN_V1", "manifestId": "b" * 64}))
        with self.assertRaisesRegex(ValueError, "receipt"):
            S.prepare_offsite(data, manifest, receipt)
        receipt.write_text(json.dumps({"kind": "SFG_BACKUP_RECEIPT_TOKEN_V1", "manifestId": "a" * 64}))
        self.assertEqual(S.prepare_offsite(data, manifest, receipt)["artifacts"], len(entries))
        point = selection["recoveryPoints"][0]
        destination = self.base / "offsite-restored"; destination.mkdir()
        proof = S.restore(data / "sifangguan-ota", data / point["statePath"], destination, self.key,
                          data / point["databasePath"], point["snapshotId"])
        self.assertEqual(proof["restore"], "STATE_CONTENT_PASS")
        self.assertEqual((destination / self.photo.relative_to(self.source)).read_bytes(), self.photo.read_bytes())

    def test_failed_publication_keeps_prior_export_manifest_and_all_its_files(self):
        self.capture()
        raw = self.base / "raw"; raw.mkdir()
        raw_file = raw / "analytics-raw-2026-10-24.tar.gz.enc"
        raw_file.write_bytes(b"fixture actual encrypted raw bytes"); S.checksum(raw_file)
        export = self.base / "export"; (export / "receipts").mkdir(parents=True)
        selection = export / ".recovery-closure.json"
        S.export_closure(self.state, self.db, self.key, selection, raw)
        wanted = json.loads(selection.read_text())
        for relative in wanted["artifactPaths"]:
            category, tier, name = relative.split("/")
            source = raw / name if category == "analytics-raw" else (self.db if category == "hotel-ai-os" else self.state) / tier / name
            target = export / "data" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target); S.checksum(target)
        previous = export / "manifest-v1.json"; previous.write_bytes(b"previous accepted manifest")
        stale = export / "data/sifangguan-ota/daily/sifangguan-ota-state-daily-20261001T010000+0800.tar.gz.enc"
        stale.write_bytes(b"previous accepted old backup"); S.checksum(stale)
        def publish_write(path, payload, gid):
            if path.name == "manifest-v1.json":
                raise RuntimeError("injected atomic publication failure")
            S.atomic(path, payload)
        import sys
        fake_grp = types.SimpleNamespace(getgrnam=lambda _: types.SimpleNamespace(gr_gid=0))
        with mock.patch.dict(sys.modules, {"grp": fake_grp}), mock.patch.object(P, "ROOT", export), \
             mock.patch.object(sys, "argv", ["publisher", str(export), "test-group"]), \
             mock.patch.object(P, "write_atomic", side_effect=publish_write):
            with self.assertRaisesRegex(RuntimeError, "publication"):
                P.main()
        self.assertEqual(previous.read_bytes(), b"previous accepted manifest")
        self.assertEqual(stale.read_bytes(), b"previous accepted old backup")
        with mock.patch.dict(sys.modules, {"grp": fake_grp}), mock.patch.object(P, "ROOT", export), \
             mock.patch.object(sys, "argv", ["publisher", str(export), "test-group"]), \
             mock.patch.object(P, "write_atomic", side_effect=lambda path, payload, gid: S.atomic(path, payload)):
            P.main()
        self.assertEqual(len(json.loads(previous.read_text())["recoveryPoints"]), 1)
        self.assertFalse(stale.exists())

    def test_capacity_uses_actual_sizes_shared_filesystem_and_fails_before_prune_or_writer_stop(self):
        # All fixture roles share one filesystem: free bytes may not be counted
        # independently for staging, encrypted storage and the export copy.
        with mock.patch.object(S.shutil, "disk_usage", return_value=types.SimpleNamespace(free=1)):
            before = set(self.base.rglob("*"))
            with self.assertRaisesRegex(ValueError, "CAPACITY_INSUFFICIENT"):
                S.check_capacity(self.state, self.db, self.source, self.base, 100, 8, self.base, 20)
            self.assertEqual(before, set(self.base.rglob("*")))
        with mock.patch.object(S.shutil, "disk_usage", return_value=types.SimpleNamespace(free=10**15)):
            result = S.check_capacity(self.state, self.db, self.source, self.base, 100, 8, self.base, 20)
        self.assertEqual(len(result["filesystems"]), 1)
        self.assertEqual(result["filesystems"][0]["requiredBytes"], S.CAPACITY_RESERVE + 2*S.PACK_LIMIT + 300 + 64 + 80)
        self.assertFalse(result["compressionAssumed"])
        script = (BASE / "backup-postgres.sh").read_text()
        self.assertLess(script.index("\ncheck_backup_capacity\n"), script.index('\nrsync "${state_rsync_options[@]}"'))
        gates = [match.start() for match in re.finditer(r"\ncheck_backup_capacity\n", script)]
        self.assertEqual(len(gates), 3)
        self.assertLess(gates[1], script.index("quiesced=true"))
        self.assertGreater(gates[2], script.index("quiesced=true"))
        self.assertLess(gates[2], script.index("\nresume_writers\n"))

    def test_atomic_bundle_is_authoritative_over_torn_legacy_pair_and_never_silently_falls_back(self):
        self.capture(); raw = self.base / "raw"; raw.mkdir()
        selection_path = self.base / "closure.json"
        S.export_closure(self.state, self.db, self.key, selection_path, raw)
        selection = json.loads(selection_path.read_text())
        device = self.base / "device"; data = device / "data"; control = device / "control"
        control.mkdir(parents=True)
        entries = []
        for relative in selection["artifactPaths"]:
            category, tier, name = relative.split("/")
            source = (self.db if category == "hotel-ai-os" else self.state) / tier / name
            target = data / relative; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
            entries.append({"path": relative, "category": category, "tier": tier, **S.checked(source)})
        manifest = {"kind":"SFG_BACKUP_MANIFEST_V1", "schemaVersion":1, "manifestId":"b"*64,
                    "entries":entries, "recoveryPoints":selection["recoveryPoints"]}
        receipt = {"kind":"SFG_BACKUP_RECEIPT_TOKEN_V1", "manifestId":"b"*64}
        bundle_path = control / "accepted-bundle.json"
        bundle = {"kind":"SFG_ACCEPTED_BACKUP_BUNDLE_V1", "schemaVersion":1, "manifest":manifest, "receipt":receipt}
        S.atomic(bundle_path, S.canonical(bundle))
        legacy_manifest = control / "accepted-manifest.json"; legacy_receipt = control / "last-receipt.json"
        legacy_manifest.write_text(json.dumps(manifest)); legacy_receipt.write_text(json.dumps({**receipt, "manifestId":"a"*64}))
        proof = S.prepare_offsite(data, legacy_manifest, legacy_receipt)
        self.assertEqual((proof["authority"],proof["manifestId"]), ("ATOMIC_BUNDLE","b"*64))
        # Explicit bundle-only CLI path does not require either compatibility file.
        legacy_manifest.unlink(); legacy_receipt.unlink()
        self.assertEqual(S.prepare_offsite(data, bundle_path=bundle_path)["authority"], "ATOMIC_BUNDLE")
        alternate = self.base / "alternate"; alternate.mkdir()
        alt_manifest = alternate / "manifest.json"; alt_receipt = alternate / "receipt.json"
        alt_manifest.write_text(json.dumps(manifest)); alt_receipt.write_text(json.dumps(receipt))
        broken = copy.deepcopy(bundle); broken["receipt"]["manifestId"] = "a"*64
        S.atomic(bundle_path, S.canonical(broken))
        with self.assertRaisesRegex(ValueError,"receipt"):
            S.prepare_offsite(data,alt_manifest,alt_receipt)
        S.atomic(bundle_path,S.canonical(bundle))
        alt_manifest.write_text(json.dumps({**manifest,"manifestId":"a"*64}))
        alt_receipt.write_text(json.dumps({**receipt,"manifestId":"a"*64}))
        self.assertEqual(S.prepare_offsite(data,alt_manifest,alt_receipt)["manifestId"],"b"*64)
        bundle_path.write_bytes(b"{torn invalid bundle")
        with self.assertRaises(json.JSONDecodeError):
            S.prepare_offsite(data,alt_manifest,alt_receipt)

    def test_raw_late_revision_is_immutable_and_prior_accepted_full_manifest_still_prepares(self):
        self.capture(); raw = self.base / "raw"; raw.mkdir()
        plain = self.base / "raw.tar.gz"
        with tarfile.open(plain,"w:gz") as archive: archive.add(self.photo,arcname="2026-10-24/original.enc")
        first_temp = raw / ".analytics-raw-2026-10-24.123.tar.gz.enc"
        S.crypt(plain,first_temp,self.key)
        first = S.commit_raw_revision(raw,first_temp,"2026-10-24","a"*64)
        first_path = raw / first["rawArchive"]; old_bytes = first_path.read_bytes()
        # A late file makes a different ciphertext name. The old artifact is
        # never overwritten, even before a device commits its next bundle.
        second_temp = raw / ".analytics-raw-2026-10-24.124.tar.gz.enc"
        S.crypt(plain,second_temp,self.key)
        second = S.commit_raw_revision(raw,second_temp,"2026-10-24","b"*64)
        self.assertNotEqual(first["rawArchive"],second["rawArchive"])
        self.assertEqual(first_path.read_bytes(),old_bytes)
        decrypted = self.base / "raw-roundtrip.tar.gz"; S.crypt(first_path,decrypted,self.key,True)
        self.assertEqual(decrypted.read_bytes(),plain.read_bytes())
        selection_path = self.base / "raw-closure.json"
        S.export_closure(self.state,self.db,self.key,selection_path,raw)
        selection = json.loads(selection_path.read_text())
        entries=[]; device=self.base/"raw-device"; data=device/"data"; control=device/"control"; control.mkdir(parents=True)
        for relative in selection["artifactPaths"]:
            category,tier,name=relative.split("/")
            if name==second["rawArchive"]: continue  # Device's previous accepted manifest.
            source=raw/name if category=="analytics-raw" else (self.db if category=="hotel-ai-os" else self.state)/tier/name
            target=data/relative; target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(source,target)
            entries.append({"path":relative,"category":category,"tier":tier,**S.checked(source)})
        manifest={"kind":"SFG_BACKUP_MANIFEST_V1","schemaVersion":1,"manifestId":"a"*64,"entries":entries,"recoveryPoints":selection["recoveryPoints"]}
        bundle={"kind":"SFG_ACCEPTED_BACKUP_BUNDLE_V1","schemaVersion":1,"manifest":manifest,"receipt":{"kind":"SFG_BACKUP_RECEIPT_TOKEN_V1","manifestId":"a"*64}}
        path=control/"accepted-bundle.json"; S.atomic(path,S.canonical(bundle))
        # New revision downloaded before interruption does not disturb old refs.
        destination=data/"analytics-raw/daily"/second["rawArchive"]; shutil.copyfile(raw/second["rawArchive"],destination)
        self.assertEqual(S.prepare_offsite(data,bundle_path=path)["authority"],"ATOMIC_BUNDLE")

    def test_raw_retention_counts_source_dates_not_revision_file_count(self):
        raw=self.base/"dated-raw"; raw.mkdir()
        start=dt.date(2026,6,1)
        for offset in range(92):
            day=(start+dt.timedelta(days=offset)).isoformat()
            for revision in range(2):
                payload=(day+" encrypted fixture "+str(revision)).encode(); digest=hashlib.sha256(payload).hexdigest()
                path=raw/("analytics-raw-"+day+"-"+digest+".tar.gz.enc"); path.write_bytes(payload); S.checksum(path)
        legacy=raw/("analytics-raw-"+(start+dt.timedelta(days=91)).isoformat()+".tar.gz.enc")
        legacy.write_bytes(b"retained legacy independent cipher"); S.checksum(legacy)
        result=S.prune_raw_dates(raw,90)
        self.assertEqual((result["retainedSourceDates"],result["retainedArtifacts"],result["removed"]),(90,181,4))
        self.assertTrue(legacy.is_file())


class CapacityMeasurementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="backup-capacity-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.serial = 0

    def lengths(self, values):
        self.serial += 1
        path = self.base / (str(self.serial) + ".lengths")
        path.write_text("".join(str(value) + "\n" for value in values), encoding="ascii")
        return path

    def measure(self, state, core=(), raw=(), completed=(), pending=(), days=0, rebuild=None, rebuild_days=None, export_pending=0):
        return S.capacity_measurements(*(self.lengths(values) for values in (state, core, raw, completed, pending)), days,
            self.lengths(rebuild) if rebuild is not None else None, rebuild_days, export_pending)

    def check(self, m, db=100, free=10**15):
        with mock.patch.object(S.shutil, "disk_usage", return_value=types.SimpleNamespace(free=free)):
            return S.check_capacity(self.base, self.base, self.base, self.base, m["stateBytes"], db,
                                    self.base, m["rawBytes"], m)

    def test_large_real_tree_uses_selected_file_sizes_not_whole_tree_for_temporary_pack(self):
        tree = self.base / "tree"
        tree.mkdir()
        paths = []
        for number in range(1200):
            path = tree / (str(number) + ".bin")
            path.write_bytes(bytes([number % 255]) * (1000 + number % 900))
            paths.append(path)
        m = self.measure([path.stat().st_size for path in paths])
        self.assertEqual(m["stateEntries"], 1200)
        self.assertEqual(m["stateBytes"], sum(path.stat().st_size for path in paths))
        self.assertEqual(m["stateMaxFileBytes"], max(path.stat().st_size for path in paths))
        # Includes all-new content, headers and a 128MiB generated-manifest allowance.
        self.assertGreater(m["newStatePackBytes"], m["stateBytes"] + S.MAX_INDEX)

    def test_realistic_five_gigabyte_tree_fits_without_compression_or_dedup_discount(self):
        size, raw, pending = 5019996180, 12238019573, 5309658495
        chunk = 4 * 1024**2
        state = [chunk] * (size // chunk) + [size % chunk]
        per_day = raw // 60
        raw_sizes = [per_day] * 59 + [raw - per_day * 59]
        m = self.measure(state, raw=raw_sizes, completed=raw_sizes, pending=[pending], days=60)
        result = self.check(m, db=287996951, free=64289742848)
        required = result["filesystems"][0]["requiredBytes"]
        self.assertGreater(required, 59 * 10**9)
        self.assertLess(required, 62 * 10**9)
        self.assertFalse(result["compressionAssumed"])
        self.assertEqual(result["reserveBytesPerFilesystem"], 4 * 1024**3)
        self.assertLess(m["largestStatePackBytes"], 300 * 1024**2)
        self.assertGreater(m["newStatePackBytes"], size)

    def test_large_single_file_reserves_its_full_temporary_pack(self):
        large = 10 * 1024**3
        m = self.measure([large, 17])
        self.assertGreater(m["largestStatePackBytes"], large)
        result = self.check(m)
        self.assertGreater(result["filesystems"][0]["requiredBytes"], 4 * large)
        with self.assertRaisesRegex(ValueError, "CAPACITY_INSUFFICIENT"):
            self.check(m, free=4 * 1024**3 + large)

    def test_pack_bounds_are_independent_of_rsync_and_inventory_order(self):
        values = [60, 40, 60, 40, 99, 1, 1, 98, 2]
        results = []
        with mock.patch.object(S, "PACK_LIMIT", 100), mock.patch.object(S, "MAX_INDEX", 17):
            for order in (values, list(reversed(values)), sorted(values), values[3:] + values[:3]):
                m = self.measure(order)
                results.append((m["newStatePackBytes"], m["largestStatePackBytes"]))
                batches, batch, size = [], [], 0
                for value in order + [17]:
                    if batch and size + value > 100:
                        batches.append(batch); batch, size = [], 0
                    batch.append(value); size += value
                if batch:
                    batches.append(batch)
                actual_upper = []
                for group in batches:
                    payload = sum(((value + 511) // 512) * 512 + 4096 for value in group)
                    tar_size = ((payload + 1024 + 10239) // 10240) * 10240
                    actual_upper.append(S.gzip_upper_bound(tar_size) + 64)
                self.assertLessEqual(sum(actual_upper), m["newStatePackBytes"])
                self.assertLessEqual(max(actual_upper), m["largestStatePackBytes"])
        self.assertEqual(len(set(results)), 1)

    def test_staged_files_do_not_get_reserved_again_and_deletion_does_not_free_budget(self):
        pending = self.measure([123456], raw=[12345], completed=[12345], pending=[123456, 12345], days=1)
        staged = self.measure([123456], raw=[12345], completed=[12345], pending=[], days=1)
        a, b = self.check(pending), self.check(staged)
        self.assertEqual(a["filesystems"][0]["requiredBytes"] - b["filesystems"][0]["requiredBytes"], 123456 + 12345)
        self.assertEqual(pending["newStatePackBytes"], staged["newStatePackBytes"])
        # No planned deletions, retention quota or existing pack catalogue enters the measurement.
        self.assertEqual(staged["stagePendingBytes"], 0)

    def test_completed_raw_only_changes_archive_and_export_not_all_date_staging(self):
        m = self.measure([1000], raw=[100, 200, 300], completed=[100, 200], pending=[600], days=2)
        self.assertEqual((m["rawBytes"], m["rawCompletedBytes"], m["stagePendingBytes"]), (600, 300, 600))
        all_days = self.measure([1000], raw=[100, 200, 300], completed=[100, 200, 300], pending=[600], days=3)
        self.assertGreater(self.check(all_days)["filesystems"][0]["requiredBytes"], self.check(m)["filesystems"][0]["requiredBytes"])

    def test_online_completed_view_growth_is_fully_budgeted_and_charged_to_staging(self):
        growth = self.measure([1000], raw=[100], completed=[100, 200], pending=[50], days=1)
        same_view = self.measure([1000], raw=[100, 200], completed=[100, 200], pending=[50], days=1)
        self.assertEqual(growth["sourceRawBytes"], 100)
        self.assertEqual(growth["rawBytes"], 300)
        self.assertEqual(growth["rawCompletedBytes"], 300)
        self.assertEqual(growth["lateRawBytes"], 200)
        self.assertEqual(growth["stagePendingBytes"], 250)
        self.assertEqual(growth["rawArchiveBytes"], same_view["rawArchiveBytes"])
        self.assertEqual(self.check(growth)["filesystems"][0]["requiredBytes"] -
                         self.check(same_view)["filesystems"][0]["requiredBytes"], 200)
        self.assertFalse(self.check(growth)["compressionAssumed"])

    def test_smaller_completed_view_never_reduces_stage_budget_and_metadata_delta_is_charged(self):
        smaller = self.measure([1000], raw=[300], completed=[100], pending=[75], days=1)
        self.assertEqual((smaller["rawBytes"], smaller["lateRawBytes"], smaller["stagePendingBytes"]), (300, 0, 75))
        metadata_growth = self.measure([1000], raw=[300], completed=[300, 4096], pending=[75], days=1)
        self.assertEqual(metadata_growth["rawBytes"], 4396)
        self.assertEqual(metadata_growth["lateRawBytes"], 4096)
        self.assertEqual(metadata_growth["stagePendingBytes"], 4171)
        self.assertGreaterEqual(metadata_growth["rawArchiveBytes"], metadata_growth["rawCompletedBytes"])
        self.check(metadata_growth)

    def test_online_growth_does_not_allow_missing_date_archive_or_empty_state(self):
        with self.assertRaisesRegex(ValueError, "no date archive"):
            self.measure([1000], raw=[100], completed=[100, 200], pending=[50], days=0)
        with self.assertRaisesRegex(ValueError, "Invalid fresh source"):
            self.measure([], raw=[100], completed=[100, 200], pending=[50], days=1)

    def test_validated_raw_reuse_changes_only_rebuild_and_missing_export_budget(self):
        reused = self.measure([1000], raw=[300], completed=[300], pending=[75], days=1, rebuild=[], rebuild_days=0)
        rebuilt = self.measure([1000], raw=[300], completed=[300], pending=[75], days=1)
        self.assertEqual(reused["rawArchiveBytes"], 0)
        for key in ("stateBytes", "newStatePackBytes", "largestStatePackBytes", "rawBytes", "rawCompletedBytes", "stagePendingBytes"):
            self.assertEqual(reused[key], rebuilt[key])
        self.assertEqual(self.check(rebuilt)["filesystems"][0]["requiredBytes"] - self.check(reused)["filesystems"][0]["requiredBytes"],
                         3 * rebuilt["rawArchiveBytes"])
        backlog = self.measure([1000], raw=[300], completed=[300], pending=[75], days=1, rebuild=[], rebuild_days=0, export_pending=777)
        self.assertEqual(self.check(backlog)["filesystems"][0]["requiredBytes"] - self.check(reused)["filesystems"][0]["requiredBytes"], 777)

    def test_raw_export_backlog_validates_sources_and_charges_entire_bad_target_copy(self):
        root, export = self.base / "raw-local", self.base / "raw-export"
        root.mkdir(); export.mkdir()
        payload = b"a real locally retained encrypted package"
        source = root / ("analytics-raw-2026-10-25-" + hashlib.sha256(payload).hexdigest() + ".tar.gz.enc")
        source.write_bytes(payload)
        S.checksum(source)
        expected = source.stat().st_size + Path(str(source) + ".sha256").stat().st_size
        self.assertEqual(S.raw_export_pending(root, export), expected)
        target = export / source.name
        shutil.copyfile(source, target); shutil.copyfile(str(source) + ".sha256", str(target) + ".sha256")
        self.assertEqual(S.raw_export_pending(root, export), 0)
        target.write_bytes(b"x" * source.stat().st_size)
        self.assertEqual(S.raw_export_pending(root, export), expected)
        # Corrupt export bytes still occupy free space; neither deletion credit
        # nor only a small size-difference may replace the full .part copy bound.
        target.write_bytes(b"x" * 100000)
        self.assertEqual(S.raw_export_pending(root, export), expected)
        source.write_bytes(b"corrupt local package")
        with self.assertRaisesRegex(ValueError, "checksum"):
            S.raw_export_pending(root, export)

    def test_validated_reuse_does_not_remove_late_source_staging_charge(self):
        m = self.measure([1000], raw=[100], completed=[300], pending=[75], days=1, rebuild=[], rebuild_days=0)
        self.assertEqual((m["rawBytes"], m["lateRawBytes"], m["stagePendingBytes"]), (300, 200, 275))
        self.assertEqual(m["rawArchiveBytes"], 0)
        self.check(m)

    @unittest.skipIf(os.name == "nt", "Linux GNU find/stat/sha256 cache condition; CI must execute")
    def test_native_raw_cache_hit_missing_package_corrupt_source_and_late_file_rebuild(self):
        script = (BASE / "backup-postgres.sh").read_text()
        function = script[script.index("raw_cache_reusable() {"):script.index("\ncheck_backup_capacity() (")]
        root, day = self.base / "raw-backups", self.base / "source-raw/2026-10-25"
        root.mkdir(); day.mkdir(parents=True)
        (day / "first.enc").write_bytes(b"actual encrypted source")
        payload = b"retained package bytes"
        source = root / ("analytics-raw-2026-10-25-" + hashlib.sha256(payload).hexdigest() + ".tar.gz.enc")
        source.write_bytes(payload); S.checksum(source)
        command = 'set -Eeuo pipefail\nraw_backup_root=$1\n' + function + '\nif raw_cache_reusable "$2" 2026-10-25; then echo HIT; else echo REBUILD; fi\nprintf "%s\\n" "$source_fingerprint"\n'
        def run():
            return subprocess.run(["bash", "-c", command, "fixture", str(root), str(day)], check=True,
                                  capture_output=True, text=True).stdout.splitlines()
        first = run()
        self.assertEqual(first[0], "REBUILD")
        cache = root / ".analytics-raw-2026-10-25.source.sha256"
        cache.write_text(first[1] + " " + source.name + "\n", encoding="ascii")
        self.assertEqual(run()[0], "HIT")
        (day / "late.enc").write_bytes(b"new arrival after the online first gate")
        late = run(); self.assertEqual(late[0], "REBUILD")
        cache.write_text(late[1] + " " + source.name + "\n", encoding="ascii")
        self.assertEqual(run()[0], "HIT")
        source.write_bytes(b"bad source sha")
        self.assertEqual(run()[0], "REBUILD")
        with self.assertRaises(ValueError):
            S.raw_export_pending(root, self.base / "missing-export")
        source.unlink()
        self.assertEqual(run()[0], "REBUILD")

    def test_same_filesystem_sums_roles_once_and_low_space_does_not_mutate_files(self):
        m = self.measure([1234, 555], raw=[4321], completed=[4321], pending=[111], days=1)
        actual = self.check(m, db=100)["filesystems"]
        self.assertEqual(len(actual), 1)
        expected = (S.CAPACITY_RESERVE + 2 * m["newStatePackBytes"] + 2 * m["largestStatePackBytes"] +
                    9 * S.MAX_INDEX + 8 * 100 + m["stagePendingBytes"] + 3 * m["rawArchiveBytes"])
        self.assertEqual(actual[0]["requiredBytes"], expected)
        before = {p: p.read_bytes() for p in self.base.iterdir() if p.is_file()}
        with self.assertRaisesRegex(ValueError, "before writer stop/prune"):
            self.check(m, db=100, free=expected - 1)
        self.assertEqual(before, {p: p.read_bytes() for p in self.base.iterdir() if p.is_file()})

    def test_numeric_logs_and_mismatched_measurements_fail_closed(self):
        for content in ("12\nfilename\n", "-100\n", "1,234\n", "4\ndeleting secret\n", "1\n\n"):
            path = self.lengths([])
            path.write_text(content, encoding="ascii")
            with self.assertRaises(ValueError):
                S.numeric_lengths(path)
        m = self.measure([1234])
        for key, value in (("stateBytes", 1), ("largestStatePackBytes", 0), ("stagePendingBytes", -1), ("newStatePackBytes", 5)):
            tampered = {**m, key: value}
            with self.assertRaises(ValueError):
                S.check_capacity(self.base, self.base, self.base, self.base, 1234, 100, self.base, 0, tampered)

    def test_machine_operations_count_sends_and_receives_without_any_deletion_credit(self):
        path = self.lengths([])
        path.write_text("send 123\nrecv 456\ndel. 999999999\nsend 0\n", encoding="ascii")
        self.assertEqual(S.numeric_lengths(path), [123, 456, 0])
        for content in ("*deleting private-name\n", "created directory private-target\n", "del. 1 private-name\n", "send -1\n", "unknown 100\n",
                        "send 1\nprivate-name\n", "skipping non-regular file private-name\n", "del. 9223372036854775808\n"):
            path.write_text(content, encoding="ascii")
            with self.assertRaises(ValueError):
                S.numeric_lengths(path)

    @unittest.skipIf(os.name == "nt" and not shutil.which("rsync"), "Windows host lacks native rsync; Linux CI must run this contract")
    def test_native_rsync_selection_pending_and_deleted_filename_are_numeric_only(self):
        self.assertIsNotNone(shutil.which("rsync"), "Linux backup contract requires actual rsync, never a silent skip")
        script = (BASE / "backup-postgres.sh").read_text()
        def options(name):
            match = re.search(name + r"=\((.*?)\)", script, re.S)
            self.assertIsNotNone(match)
            return shlex.split(match[1])
        state_options, raw_options = options("state_rsync_options"), options("raw_rsync_options")
        source, raw, target = (self.base / name for name in ("native-source", "native-raw", "native-stage"))
        source.mkdir(); raw.mkdir(); target.mkdir()
        for relative, content in (("profile/Cookies", b"keep-session"), ("profile/Cache/drop.bin", b"cache" * 100),
                                  ("analytics-retention/raw/drop.enc", b"raw-not-in-state")):
            path = source / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
        (source / "empty-directory").mkdir()
        os.mkfifo(source / "profile/special-fifo")
        special_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(special_socket.close)
        special_socket.bind(str(source / "profile/special-socket"))
        for relative in ("2026-10-25/keep.enc", "2026-10-25/drop.txt", "2026-10-27/today.enc"):
            path = raw / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b"12345")
        def dry(arguments, src, dst):
            env = {**os.environ, "LC_ALL": "C"}
            # Match production: an existing empty destination avoids rsync's
            # independent human 'created directory' stdout status record.
            dst.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(["rsync", *arguments, "--dry-run", "--info=NONE,NAME1", "--out-format=%o %l", str(src) + "/", str(dst) + "/"],
                                    check=True, capture_output=True, text=True, env=env)
            self.last_dry_output = result.stdout
            path = self.lengths([]); path.write_text(result.stdout, encoding="ascii")
            return S.numeric_lengths(path)
        fresh = dry(state_options, source, self.base / "fresh-destination")
        self.assertIn(len(b"keep-session"), fresh)
        self.assertNotIn(len(b"cache" * 100), fresh)
        subprocess.run(["rsync", *state_options, str(source) + "/", str(target) + "/"], check=True, capture_output=True)
        (target / "stale-filename-with-newline\nnot-a-number").write_bytes(b"never deletion credit")
        (target / "old-cache/nested").mkdir(parents=True)
        (target / "old-cache/nested/stale.bin").write_bytes(b"deleted bytes are not available credit")
        pending = dry(state_options, source, target)
        self.assertIn("del. ", self.last_dry_output)
        self.assertNotIn("stale-filename", self.last_dry_output)
        self.assertNotIn("special-fifo", self.last_dry_output)
        self.assertNotIn("created ", self.last_dry_output)
        self.assertFalse((target / "profile/special-fifo").exists())
        self.assertFalse((target / "profile/special-socket").exists())
        self.assertLess(sum(pending), sum(fresh))
        self.assertFalse((target / "profile/Cache/drop.bin").exists())
        raw_fresh = dry(raw_options, raw, self.base / "fresh-raw")
        self.assertEqual(raw_fresh.count(5), 2)
        self.assertEqual(dry(raw_options, raw / "2026-10-25", self.base / "fresh-completed").count(5), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
