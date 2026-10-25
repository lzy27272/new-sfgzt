"""Real local encryption/dedup/restore tests. Not a production PG rehearsal."""
import copy
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
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
        self.assertLess(script.index("\ncheck_backup_capacity\n"), script.index('rsync "${state_rsync_options[@]}"'))
        self.assertLess(script.rindex("\ncheck_backup_capacity\n"), script.index("quiesced=true"))

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
