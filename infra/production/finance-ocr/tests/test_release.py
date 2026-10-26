"""Portable filesystem/command contracts; no production paths, users or services are changed."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ocr_release", ROOT / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SHA = "a" * 40
TOKEN = "server-generated-private-fixture-token-00000000000000000000001"


class WindowsPointer:
    """Only the test fixture: Windows lacks symlink privilege; Linux uses real symlinks."""
    def __init__(self, path):
        self.path = path

    def exists(self):
        return self.path.exists()

    def is_symlink(self):
        return self.path.exists()

    def resolve(self, strict=False):
        target = Path(self.path.read_text(encoding="utf-8"))
        return target.resolve(strict=strict)

    def symlink_to(self, target, target_is_directory=False):
        self.path.write_text(str(target), encoding="utf-8")

    def unlink(self):
        self.path.unlink()


class LocalDeployment(release.Deployment):
    def __init__(self, paths):
        super().__init__(paths)
        self.actions = []
        self.services = {release.CORE_SERVICE: False, release.SERVICE: True}
        self.is_enabled = True
        self.fail_restart = False
        self.fail_probe = False
        self.wrong_source = False

    def secure(self, path, *, directory=False):
        value = path.lstat()
        if path.is_symlink() or (not path.is_dir() if directory else not path.is_file()):
            raise ValueError("Unsafe fixture node")
        # Windows chmod does not expose POSIX group bits; production permission
        # validation is separately exercised with explicit stat fixtures below.
        return types.SimpleNamespace(st_mode=(stat.S_IFDIR | 0o700) if directory else (stat.S_IFREG | 0o600),
            st_uid=0, st_gid=0, st_nlink=1, st_size=value.st_size)

    def atomic(self, path, content, *, mode=0o600, gid=0):
        if path.is_symlink():
            raise ValueError("Unsafe fixture destination")
        pending = path.with_name("." + path.name + ".pending")
        pending.write_bytes(content)
        os.replace(pending, path)

    def command(self, args, **kwargs):
        self.actions.append(args)
        if args[0] == "/usr/bin/ss":
            return subprocess.CompletedProcess(args, 0, "LISTEN 0 5 127.0.0.1:8095 0.0.0.0:*\n", "")
        action = args[1]
        if action == "is-active":
            return subprocess.CompletedProcess(args, 0 if self.services.get(args[-1], False) else 3, "", "")
        if action == "is-enabled":
            return subprocess.CompletedProcess(args, 0 if self.is_enabled else 1, "enabled\n" if self.is_enabled else "disabled\n", "")
        if action == "restart" and self.fail_restart:
            self.fail_restart = False
            raise RuntimeError("Synthetic worker startup failure")
        if action in ("stop", "restart", "start"):
            self.services[args[-1]] = action != "stop"
        if action in ("enable", "disable"):
            self.is_enabled = action == "enable"
        return subprocess.CompletedProcess(args, 0, "", "")

    def wait(self, port):
        self.actions.append(["wait", port])

    def running_source(self, service, candidate):
        self.actions.append(["runningSource", service])
        if self.wrong_source:
            raise ValueError("OCR process is not running from the selected source tree")

    def process_directory(self, service):
        return self.current() / "app" if self.current() and self.services.get(service, False) else None

    def link(self, target):
        if isinstance(self.p.current, WindowsPointer):
            self.p.current.symlink_to(target)
        else:
            super().link(target)

    def probe(self, candidate, port, token):
        self.actions.append(["syntheticProbe", port])
        if self.fail_probe:
            self.fail_probe = False
            raise RuntimeError("Synthetic HTTP acceptance failure")
        if token != TOKEN:
            raise ValueError("Unexpected token")
        return {"status": "PASS", "syntheticOnly": True, "checks": {"hash": True, "actualText": True}}


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ocr-release-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.paths = release.Paths(Path(self.temporary.name))
        if os.name == "nt":
            self.paths.current = WindowsPointer(self.paths.current)
        self.deployment = LocalDeployment(self.paths)
        for path in (self.paths.stage, self.paths.releases, self.paths.state, self.paths.library,
                     self.paths.core_env.parent, self.paths.service_file.parent):
            path.mkdir(parents=True, exist_ok=True)
        self.old_core = b"DATABASE_PASSWORD=private-value-never-logged\nUNRELATED_SETTING=true\n"
        self.paths.core_env.write_bytes(self.old_core)
        self.paths.worker_env.write_text("FINANCE_OCR_TOKEN=" + TOKEN + "\n", encoding="ascii")
        self.paths.service_file.write_bytes(b"previous immutable service\n")
        (self.paths.library / release.SERVICE).write_bytes(b"fixed trusted service\nUser=hotelai\n")
        self.previous = self.paths.releases / "previous"
        self.previous.mkdir()
        self.paths.current.symlink_to(self.previous, target_is_directory=True)
        self.candidate, self.state = self.deployment.ids("candidate", SHA)
        (self.candidate / "app").mkdir(parents=True)
        (self.candidate / "deployment").mkdir()
        (self.candidate / "deployment" / release.SERVICE).write_bytes((self.paths.library / release.SERVICE).read_bytes())
        (self.candidate / "app/requirements.lock").write_bytes(b"real==1 --hash=sha256:fixture\n")
        self.state.mkdir()
        (self.state / "candidate.env").write_text("FINANCE_OCR_TOKEN=" + TOKEN + "\n", encoding="ascii")
        self.identity = {"sourceCommit": SHA, "bundleSha256": "b" * 64, "bundleManifestSha256": "c" * 64,
                         "requirementsSha256": release.digest(self.candidate / "app/requirements.lock"),
                         "pythonMajorMinor": "3.12", "platform": "linux-x86_64"}
        bundle_manifest = {"sourceCommit": SHA, "files": [{"file": "app/requirements.lock", "sizeBytes": (self.candidate / "app/requirements.lock").stat().st_size,
            "sha256": self.identity["requirementsSha256"]}]}
        self.deployment.save(self.candidate / "bundle-manifest.json", bundle_manifest)
        self.identity["bundleManifestSha256"] = release.digest(self.candidate / "bundle-manifest.json")
        self.deployment.save(self.state / "record.json", {"sourceCommit": SHA, "releaseId": "candidate", "phase": "PREPARED", "identity": self.identity})
        core_release = self.paths.root / "opt/hotel-ai-os/current"
        core_release.mkdir()
        self.deployment.save(core_release / ".finance-ocr.json", {"releaseId": "candidate", **self.identity})
        verifier_path = ROOT.parents[2] / "tools/deployment/finance_ocr_bundle.py"
        (self.paths.library / "finance_ocr_bundle.py").write_bytes(verifier_path.read_bytes())

    def test_switch_only_after_stopped_core_and_real_probe(self):
        self.deployment.services[release.CORE_SERVICE] = True
        with self.assertRaisesRegex(ValueError, "Stop Core"):
            self.deployment.activate("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertFalse((self.state / "rollback.json").exists())
        self.deployment.services[release.CORE_SERVICE] = False
        self.deployment.activate("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.candidate)
        self.assertIn(["syntheticProbe", 8095], self.deployment.actions)
        self.assertEqual(self.deployment.load(self.state / "record.json")["phase"], "ACTIVE")
        self.assertIn(b"UNRELATED_SETTING=true\n", self.paths.core_env.read_bytes())
        self.assertNotIn(TOKEN, json.dumps(self.deployment.actions))

    def test_failed_worker_start_restores_exact_files_pointer_and_active_state(self):
        self.deployment.fail_restart = True
        with self.assertRaisesRegex(RuntimeError, "startup failure"):
            self.deployment.activate("candidate", SHA)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual(self.paths.service_file.read_bytes(), b"previous immutable service\n")
        self.assertTrue(self.deployment.services[release.SERVICE])
        self.assertEqual(self.deployment.load(self.state / "record.json")["phase"], "ROLLED_BACK")
        self.assertFalse(any("postgres" in str(a) or "migrat" in str(a) for a in self.deployment.actions))

    def test_failed_http_smoke_also_restores_previous_worker_and_core_env(self):
        self.deployment.fail_probe = True
        with self.assertRaisesRegex(RuntimeError, "HTTP acceptance"):
            self.deployment.activate("candidate", SHA)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertTrue(self.deployment.services[release.SERVICE])

    def test_explicit_rollback_preserves_new_immutable_release_for_diagnosis(self):
        self.deployment.activate("candidate", SHA)
        self.deployment.rollback("candidate", SHA)
        self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertTrue(self.candidate.exists())
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)

    def test_prepared_rollback_is_strictly_read_only_and_preserves_the_live_worker(self):
        before = {str(path): path.read_bytes() for path in self.paths.root.rglob("*") if path.is_file()}
        services = dict(self.deployment.services)
        self.deployment.rollback("candidate", SHA)
        after = {str(path): path.read_bytes() for path in self.paths.root.rglob("*") if path.is_file()}
        self.assertEqual(before, after)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual(self.deployment.services, services)
        self.assertTrue(all(args[1] in ("is-active", "show") for args in self.deployment.actions))
        self.assertEqual(self.deployment.load(self.state / "record.json")["phase"], "PREPARED")

    def test_failed_pre_activation_snapshot_remains_a_read_only_noop(self):
        self.deployment.snapshot(self.state)
        self.deployment.atomic(self.state / "next-core.env", release.core_configuration(self.old_core, TOKEN))
        original_record = (self.state / "record.json").read_bytes()
        self.deployment.actions.clear()
        self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual((self.state / "record.json").read_bytes(), original_record)
        self.assertTrue(all(args[1] in ("is-active", "show") for args in self.deployment.actions))

    def test_prepared_rollback_rejects_live_candidate_pointer_without_mutating_it(self):
        self.deployment.link(self.candidate)
        with self.assertRaisesRegex(ValueError, "activation traces"):
            self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.candidate)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertEqual(self.deployment.actions, [])

    def test_prepared_rollback_rejects_candidate_process_even_if_pointer_is_old(self):
        with patch.object(self.deployment, "process_directory", return_value=self.candidate / "app"):
            with self.assertRaisesRegex(ValueError, "already running"):
                self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertTrue(all(args[1] in ("is-active", "show") for args in self.deployment.actions))

    def test_prepared_rollback_rejects_a_changed_configuration_after_snapshot(self):
        self.deployment.snapshot(self.state)
        self.paths.core_env.write_bytes(release.core_configuration(self.old_core, TOKEN))
        changed = self.paths.core_env.read_bytes()
        self.deployment.actions.clear()
        with self.assertRaisesRegex(ValueError, "Configuration changed before"):
            self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.core_env.read_bytes(), changed)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertTrue(all(args[1] in ("is-active", "show") for args in self.deployment.actions))

    def test_prepared_rollback_rejects_inconsistent_activation_proof(self):
        self.deployment.save(self.state / "active-proof.json", {"status": "PASS"})
        with self.assertRaisesRegex(ValueError, "activation traces"):
            self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), self.previous)
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertEqual(self.deployment.actions, [])

    def test_first_install_rollback_removes_only_new_service_env_and_link(self):
        self.paths.current.unlink()
        self.paths.service_file.unlink()
        self.paths.worker_env.unlink()
        self.deployment.services[release.SERVICE] = False
        self.deployment.is_enabled = False
        self.deployment.fail_restart = True
        with self.assertRaises(RuntimeError):
            self.deployment.activate("candidate", SHA)
        self.assertFalse(self.paths.current.is_symlink())
        self.assertFalse(self.paths.worker_env.exists())
        self.assertFalse(self.paths.service_file.exists())
        self.assertEqual(self.paths.core_env.read_bytes(), self.old_core)
        self.assertFalse(self.deployment.services[release.SERVICE])

    def test_changed_core_configuration_blocks_unsafe_late_rollback(self):
        self.deployment.activate("candidate", SHA)
        self.paths.core_env.write_bytes(self.paths.core_env.read_bytes() + b"NEW_INDEPENDENT_SETTING=yes\n")
        before = len(self.deployment.actions)
        with self.assertRaisesRegex(ValueError, "changed after activation"):
            self.deployment.rollback("candidate", SHA)
        self.assertEqual(len(self.deployment.actions), before)
        self.assertEqual(self.paths.current.resolve(), self.candidate)

    def test_another_active_release_is_never_overwritten(self):
        self.deployment.activate("candidate", SHA)
        another = self.paths.releases / "later"
        another.mkdir()
        self.deployment.link(another)
        with self.assertRaisesRegex(ValueError, "Another OCR release"):
            self.deployment.rollback("candidate", SHA)
        self.assertEqual(self.paths.current.resolve(), another)

    def test_candidate_cannot_supply_a_root_service_unit(self):
        (self.candidate / "deployment" / release.SERVICE).write_bytes(b"[Service]\nUser=root\nExecStart=/bin/sh\n")
        with self.assertRaisesRegex(ValueError, "administrator-installed"):
            self.deployment.trusted_service(self.candidate)

    def test_id_source_and_requirements_drift_are_rejected(self):
        for name in ("../outside", "a..b", " space", "a/b"):
            with self.assertRaises(ValueError):
                self.deployment.ids(name, SHA)
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.deployment.record("candidate", "f" * 40, ("PREPARED",))
        (self.candidate / "app/requirements.lock").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "requirements changed"):
            self.deployment.record("candidate", SHA, ("PREPARED",))

    def test_tampered_rollback_snapshot_cannot_be_applied(self):
        self.deployment.activate("candidate", SHA)
        (self.state / "previous-core.env").write_bytes(b"TAMPERED=true\n")
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.deployment.rollback("candidate", SHA)
        self.assertIn(b"FINANCE_LOCAL_OCR_ENDPOINT", self.paths.core_env.read_bytes())

    def test_final_verification_requires_actual_identity_and_commits_only_after_probe(self):
        self.deployment.activate("candidate", SHA)
        self.deployment.verify("candidate", SHA)
        self.assertTrue((self.state / "active-proof.json").exists())
        self.assertTrue(self.deployment.load(self.state / "active-proof.json")["syntheticOnly"])
        self.deployment.commit("candidate", SHA)
        self.assertEqual(self.deployment.load(self.state / "record.json")["phase"], "COMMITTED")
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.deployment.rollback("candidate", SHA)

    def test_final_verification_rejects_core_worker_identity_mismatch(self):
        self.deployment.activate("candidate", SHA)
        self.deployment.save(self.paths.root / "opt/hotel-ai-os/current/.finance-ocr.json", {"sourceCommit": "f" * 40})
        with self.assertRaisesRegex(ValueError, "Core release OCR identity"):
            self.deployment.verify("candidate", SHA)
        self.assertFalse((self.state / "active-proof.json").exists())

    def test_final_verification_rejects_wrong_running_source_and_does_not_write_pass(self):
        self.deployment.activate("candidate", SHA)
        self.deployment.wrong_source = True
        with self.assertRaisesRegex(ValueError, "selected source tree"):
            self.deployment.verify("candidate", SHA)
        self.assertFalse((self.state / "active-proof.json").exists())

    def test_manifest_requires_matching_outer_digest_platform_and_source(self):
        bundle = self.paths.stage / "finance-ocr.zip"
        bundle.write_bytes(b"fixture archive bytes")
        identity = {**self.identity, "bundleSha256": release.digest(bundle)}
        manifest = {"status": "PASS", "lane": "FULL", "sourceCommit": SHA, "financeOcr": identity,
                    "artifacts": [{"name": "finance-ocr", "file": "finance-ocr.zip", "sha256": identity["bundleSha256"], "sizeBytes": bundle.stat().st_size}]}
        self.deployment.save(self.paths.stage / "release-manifest.json", manifest)
        self.assertEqual(self.deployment.approved(SHA), identity)
        bundle.write_bytes(b"tampered same fixture")
        with self.assertRaisesRegex(ValueError, "immutable artifact"):
            self.deployment.approved(SHA)
        bundle.write_bytes(b"fixture archive bytes")
        manifest["financeOcr"]["platform"] = "windows-amd64"
        self.deployment.save(self.paths.stage / "release-manifest.json", manifest)
        with self.assertRaisesRegex(ValueError, "Linux OCR"):
            self.deployment.approved(SHA)

    def test_security_check_rejects_world_writable_or_symlink_inputs(self):
        fixture = types.SimpleNamespace(lstat=lambda: types.SimpleNamespace(st_mode=stat.S_IFREG | 0o622, st_uid=0, st_nlink=1))
        with self.assertRaisesRegex(ValueError, "Unsafe root-controlled"):
            release.Deployment.secure(self.deployment, fixture)
        fixture.lstat = lambda: types.SimpleNamespace(st_mode=stat.S_IFLNK | 0o777, st_uid=0, st_nlink=1)
        with self.assertRaises(ValueError):
            release.Deployment.secure(self.deployment, fixture)

    def test_configuration_is_narrow_idempotent_and_never_auto_enables_external_endpoint(self):
        original = b"A=secret\r\nFINANCE_OCR_TOKEN=old\r\nFINANCE_LOCAL_OCR_ENDPOINT=https://obsolete/\n# untouched\nB=2"
        result = release.core_configuration(original, TOKEN)
        self.assertTrue(result.startswith(b"A=secret\r\n# untouched\nB=2\n"))
        self.assertEqual(release.core_configuration(result, TOKEN), result)
        self.assertIn(b"http://127.0.0.1:8095/recognize", result)
        self.assertEqual(result.count(b"FINANCE_OCR_TOKEN="), 1)
        with self.assertRaises(ValueError):
            release.token_from(b"FINANCE_OCR_TOKEN=abc\nOTHER=value\n")

    def test_fixed_helper_preserves_full_gates_and_never_runs_candidate_python_as_root(self):
        code = (ROOT / "release.py").read_text(encoding="utf-8")
        self.assertIn('"/usr/sbin/runuser", "-u", "hotelai"', code)
        self.assertIn('"--no-index"', code)
        self.assertIn('"--require-hashes"', code)
        self.assertIn('"--only-binary=:all:"', code)
        self.assertNotIn('"apt"', code)
        self.assertNotIn('"psql"', code)
        self.assertNotIn('"MARK_FULL"', code)
        service = (ROOT / release.SERVICE).read_text(encoding="utf-8")
        self.assertIn("User=hotelai", service)
        self.assertIn("FINANCE_OCR_HOST=127.0.0.1", service)
        self.assertIn("IPAddressDeny=any", service)
        bootstrap = ROOT.parent / "bootstrap"
        full = (bootstrap / "60-install-release.sh").read_text(encoding="utf-8")
        final = (bootstrap / "100-final-audit.sh").read_text(encoding="utf-8")
        self.assertIn('ASSERT_PREPARED "${release_id}" "${source_commit}"', full)
        self.assertIn('RESUME_FULL "${source_commit}" "${release_id}"', full)
        self.assertLess(final.index('VERIFY "${verified_release_id}"'), final.index('MARK_FULL "${verified_full_commit}"'))

    def test_candidate_commands_do_not_inherit_migration_or_ssh_secrets(self):
        captured = {}
        def run(args, **kwargs):
            captured.update(kwargs)
            return subprocess.CompletedProcess(args, 0, "", "")
        with patch.dict(os.environ, {"PGPASSWORD": "private-owner-password", "DEPLOY_SSH_KEY": "private-key"}):
            release.Deployment(runner=run).command(["/usr/sbin/runuser", "-u", "hotelai", "--", "candidate-python"])
        self.assertNotIn("PGPASSWORD", captured["env"])
        self.assertNotIn("DEPLOY_SSH_KEY", captured["env"])
        self.assertNotIn("FINANCE_OCR_TOKEN", captured["env"])


if __name__ == "__main__":
    unittest.main()
