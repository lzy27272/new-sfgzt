"""Synthetic archive fixtures only; no OCR model or CI PASS is simulated for release."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("finance_ocr_bundle", Path(__file__).parents[1] / "finance_ocr_bundle.py")
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)
COMMIT = "a" * 40


class BundleContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ocr-bundle-contract-")
        self.root = Path(self.temporary.name)
        self.files = {name: b"fixture\n" for name in BUNDLE.REQUIRED_FILES}
        models = {kind: (kind + ".onnx", hashlib.sha256(kind.encode()).hexdigest()) for kind in ("det", "rec", "cls")}
        self.files["app/finance_ocr/models.py"] = ("MODELS = " + repr(models) + "\n").encode()
        wheel = io.BytesIO()
        with zipfile.ZipFile(wheel, "w") as archive:
            archive.writestr("rapidocr-3.10.0.dist-info/METADATA", "Name: rapidocr\nVersion: 3.10.0\n")
            for kind, (name, _) in models.items():
                archive.writestr("rapidocr/models/" + name, kind.encode())
        self.files["wheels/rapidocr-3.10.0-py3-none-any.whl"] = wheel.getvalue()
        self.files["app/requirements.lock"] = ("--only-binary=:all:\n--require-hashes\nrapidocr==3.10.0 "
            "--hash=sha256:" + BUNDLE.digest(wheel.getvalue()) + "\n").encode()
        self.files["evidence/linux-smoke.json"] = json.dumps({"status": "PASS", "syntheticOnly": True,
            "checks": dict.fromkeys(BUNDLE.SMOKE_CHECKS, True)}).encode()
        self.files["evidence/unittest.log"] = b"....\nRan 28 tests in 1.0s\n\nOK\n"

    def tearDown(self):
        self.temporary.cleanup()

    def manifest(self, files=None):
        files = self.files if files is None else files
        return {"schemaVersion": 1, "status": "PASS", "sourceCommit": COMMIT,
                "pythonMajorMinor": "3.12", "platform": "linux-x86_64",
                "requirementsSha256": BUNDLE.digest(files["app/requirements.lock"]),
                "models": BUNDLE.model_contract(files["app/finance_ocr/models.py"]),
                "files": [{"file": name, "sha256": BUNDLE.digest(content), "sizeBytes": len(content)}
                          for name, content in sorted(files.items())]}

    def archive(self, files=None, manifest=None, extra=None):
        files = self.files if files is None else files
        path = self.root / ("fixture-" + str(len(list(self.root.glob("*.zip")))) + ".zip")
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in files.items():
                archive.writestr(name, content)
            archive.writestr("bundle-manifest.json", json.dumps(manifest or self.manifest(files)))
            if extra:
                archive.writestr(*extra)
        return path

    def test_complete_fixture_validates(self):
        result = BUNDLE.verify_bundle(self.archive(), COMMIT)
        self.assertEqual((result["status"], result["wheelCount"]), ("PASS", 1))

    def test_wrong_commit_rejected(self):
        with self.assertRaisesRegex(ValueError, "commit"):
            BUNDLE.verify_bundle(self.archive(), "b" * 40)

    def test_missing_offline_wheel_rejected(self):
        files = {name: content for name, content in self.files.items() if not name.startswith("wheels/")}
        with self.assertRaisesRegex(ValueError, "closure"):
            BUNDLE.verify_bundle(self.archive(files), COMMIT)

    def test_tampered_member_rejected(self):
        manifest = self.manifest()
        self.files["app/finance_ocr/server.py"] = b"changed"
        with self.assertRaisesRegex(ValueError, "hash or size"):
            BUNDLE.verify_bundle(self.archive(manifest=manifest), COMMIT)

    def test_incorrect_model_declaration_rejected(self):
        manifest = self.manifest()
        manifest["models"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "model"):
            BUNDLE.verify_bundle(self.archive(manifest=manifest), COMMIT)

    def test_forged_wheel_hash_rejected(self):
        self.files["app/requirements.lock"] = self.files["app/requirements.lock"].split(b"--hash=sha256:")[0] + b"--hash=sha256:" + b"0" * 64 + b"\n"
        with self.assertRaisesRegex(ValueError, "hash lock"):
            BUNDLE.verify_bundle(self.archive(), COMMIT)

    def test_failure_or_skipped_unit_evidence_rejected(self):
        for log in (b"Ran 28 tests in 1s\nFAILED (failures=1)\n", b"Ran 28 tests in 1s\nOK (skipped=1)\n", b"OK\n"):
            self.files["evidence/unittest.log"] = log
            with self.assertRaisesRegex(ValueError, "completion"):
                BUNDLE.verify_bundle(self.archive(), COMMIT)

    def test_false_real_smoke_rejected(self):
        smoke = json.loads(self.files["evidence/linux-smoke.json"])
        smoke["checks"]["invoiceNumber"] = False
        self.files["evidence/linux-smoke.json"] = json.dumps(smoke).encode()
        with self.assertRaisesRegex(ValueError, "engine evidence"):
            BUNDLE.verify_bundle(self.archive(), COMMIT)

    def test_unsafe_or_unknown_member_rejected(self):
        for name in ("../escape", "app/../escape", "app\\escape", "token.env"):
            with self.assertRaises(ValueError):
                BUNDLE.verify_bundle(self.archive(extra=(name, b"unsafe")), COMMIT)

    def test_link_member_rejected(self):
        entry = zipfile.ZipInfo("link")
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(ValueError, "nonregular"):
            BUNDLE.verify_bundle(self.archive(extra=(entry, b"target")), COMMIT)

    def test_duplicate_member_rejected(self):
        with self.assertWarns(UserWarning):
            path = self.archive(extra=("app/requirements.lock", b"duplicate"))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            BUNDLE.verify_bundle(path, COMMIT)

    def test_bad_platform_rejected(self):
        manifest = self.manifest()
        manifest["platform"] = "windows-amd64"
        with self.assertRaisesRegex(ValueError, "platform"):
            BUNDLE.verify_bundle(self.archive(manifest=manifest), COMMIT)

    def test_release_expected_hashes_enforced(self):
        path = self.archive()
        with self.assertRaisesRegex(ValueError, "identity"):
            BUNDLE.checked_verify(path, COMMIT, "0" * 64)

    def test_extract_is_verified_and_never_overwrites(self):
        path = self.archive()
        target = self.root / "stage"
        result = BUNDLE.extract_bundle(path, target, COMMIT)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual((target / "app/requirements.lock").read_bytes(), self.files["app/requirements.lock"])
        with self.assertRaises(FileExistsError):
            BUNDLE.extract_bundle(path, target, COMMIT)
        self.assertEqual((target / "app/requirements.lock").read_bytes(), self.files["app/requirements.lock"])

    def test_bad_archive_does_not_create_stage(self):
        path = self.archive(extra=("../escape", b"unsafe"))
        target = self.root / "failed-stage"
        with self.assertRaises(ValueError):
            BUNDLE.extract_bundle(path, target, COMMIT)
        self.assertFalse(target.exists())

    def manifest_process(self, artifact_root):
        script = Path(__file__).parents[1] / "New-PilotReleaseManifest.ps1"
        plan = {"schemaVersion": 1, "policyVersion": 3, "classifierSha256": "b" * 64,
                "status": "PASS", "lane": "BACKEND", "requiresWeb": False,
                "requiresCore": True, "requiresFullRegression": True, "requiresDatabaseBackup": False}
        (artifact_root / "deployment-plan.json").write_text(json.dumps(plan), encoding="utf-8")
        with zipfile.ZipFile(artifact_root / "core-api.jar", "w") as archive:
            archive.writestr("BOOT-INF/classes/db/migration/V1__fixture.sql", "select 1;")
        return subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(script),
                               "-PlanPath", str(artifact_root / "deployment-plan.json"),
                               "-SourceCommit", COMMIT, "-ArtifactRoot", str(artifact_root),
                               "-RequireFinanceOcr", "-PythonCommand", sys.executable],
                              capture_output=True, text=True, encoding="utf-8", timeout=30)

    def test_powershell_manifest_records_verified_ocr_fields(self):
        root = self.root / "release"
        root.mkdir()
        path = self.archive()
        (root / "finance-ocr.zip").write_bytes(path.read_bytes())
        result = self.manifest_process(root)
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(manifest["financeOcr"]["sourceCommit"], COMMIT)
        self.assertEqual(manifest["financeOcr"]["requirementsSha256"], BUNDLE.digest(self.files["app/requirements.lock"]))
        self.assertEqual([item["name"] for item in manifest["artifacts"]], ["core", "finance-ocr"])

    def test_powershell_manifest_blocks_tampered_ocr(self):
        root = self.root / "bad-release"
        root.mkdir()
        manifest = self.manifest()
        self.files["app/finance_ocr/server.py"] = b"changed"
        (root / "finance-ocr.zip").write_bytes(self.archive(manifest=manifest).read_bytes())
        result = self.manifest_process(root)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Finance OCR artifact validation failed", result.stderr)
        self.assertFalse((root / "release-manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
