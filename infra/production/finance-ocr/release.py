"""Root-only OCR part of the approved FULL release; never migrates or restores a database."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import secrets
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

SHA = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
RELEASE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
SERVICE = "hotel-ai-os-finance-ocr.service"
CORE_SERVICE = "hotel-ai-os-core-api.service"
OCR_KEYS = ("FINANCE_LOCAL_OCR_ENDPOINT", "FINANCE_OCR_TOKEN", "FINANCE_LOCAL_OCR_TIMEOUT_MS")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_json(content):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON property")
            result[key] = value
        return result
    return json.loads(content, object_pairs_hook=unique)


def core_configuration(content, token):
    """Preserve every unrelated setting byte-for-byte, replacing only three OCR keys."""
    text = content.decode("utf-8")
    lines = text.splitlines(keepends=True)
    kept = [line for line in lines if not any(re.match(r"^\s*(?:export\s+)?" + key + r"\s*=", line) for key in OCR_KEYS)]
    original = "".join(kept)
    if original and not original.endswith("\n"):
        original += "\n"
    return (original + "FINANCE_LOCAL_OCR_ENDPOINT=http://127.0.0.1:8095/recognize\n" +
            f"FINANCE_OCR_TOKEN={token}\nFINANCE_LOCAL_OCR_TIMEOUT_MS=15000\n").encode("utf-8")


def token_from(content):
    lines = content.decode("ascii").splitlines()
    if len(lines) != 1 or not lines[0].startswith("FINANCE_OCR_TOKEN="):
        raise ValueError("Worker environment must contain exactly its protected token")
    token = lines[0].split("=", 1)[1]
    if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token):
        raise ValueError("Unsafe worker token")
    return token


class Paths:
    def __init__(self, root=Path("/")):
        # root is only an in-process test seam. The command line never accepts a path override.
        self.root = Path(root)
        self.stage = self.root / "tmp/hotel-ai-os-release"
        self.home = self.root / "opt/hotel-ai-os/finance-ocr"
        self.releases = self.home / "releases"
        self.current = self.home / "current"
        self.state = self.root / "var/lib/hotel-ai-os-deploy/finance-ocr"
        self.library = self.root / "usr/local/lib/hotel-ai-os/finance-ocr"
        self.worker_env = self.root / "etc/hotel-ai-os/finance-ocr.env"
        self.core_env = self.root / "etc/hotel-ai-os/core-api.env"
        self.service_file = self.root / "etc/systemd/system" / SERVICE


class Deployment:
    def __init__(self, paths=None, runner=None):
        self.p = paths or Paths()
        self.runner = runner or subprocess.run

    def command(self, args, *, timeout=180, env=None, allowed=(0,)):
        # A FULL coordinator may hold migration-owner credentials in its own
        # environment. Never inherit them into candidate pip/import processes.
        if env is None:
            env = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8",
                   "PYTHONDONTWRITEBYTECODE": "1", "PYTHONUNBUFFERED": "1"}
        result = self.runner(args, env=env, timeout=timeout, capture_output=True, text=True,
                             check=False, stdin=subprocess.DEVNULL)
        if result.returncode not in allowed:
            # Never propagate subprocess stdout/stderr, pip environment, journal, or a token.
            raise RuntimeError("OCR deployment command failed: " + Path(args[0]).name)
        return result

    def secure(self, path, *, directory=False):
        metadata = path.lstat()
        predicate = stat.S_ISDIR if directory else stat.S_ISREG
        if not predicate(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode) or metadata.st_uid != 0 or metadata.st_mode & 0o022:
            raise ValueError("Unsafe root-controlled OCR deployment path")
        if not directory and metadata.st_nlink != 1:
            raise ValueError("Deployment input must have one hard link")
        return metadata

    def directory(self, path, mode=0o700):
        if path.exists() or path.is_symlink():
            self.secure(path, directory=True)
        else:
            self.secure(path.parent, directory=True)
            path.mkdir(mode=mode)
        return path

    def private_environment(self, path):
        metadata = self.secure(path)
        if stat.S_IMODE(metadata.st_mode) not in (0o600, 0o640):
            raise ValueError("OCR/Core environment must be private mode 0600 or 0640")
        return metadata

    def atomic(self, path, content, *, mode=0o600, gid=0):
        self.secure(path.parent, directory=True)
        if path.exists() or path.is_symlink():
            self.secure(path)
        fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
        try:
            os.fchmod(fd, mode)
            os.fchown(fd, 0, gid)
            with os.fdopen(fd, "wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def save(self, path, value):
        self.atomic(path, json.dumps(value, sort_keys=True, separators=(",", ":")).encode() + b"\n")

    def load(self, path):
        self.secure(path)
        if path.stat().st_size > 1048576:
            raise ValueError("Oversized OCR deployment record")
        return unique_json(path.read_bytes())

    def ids(self, release_id, commit):
        if not RELEASE.fullmatch(release_id) or ".." in release_id or not COMMIT.fullmatch(commit):
            raise ValueError("Invalid immutable release identity")
        return self.p.releases / release_id, self.p.state / release_id

    def approved(self, commit):
        self.secure(self.p.stage, directory=True)
        manifest = self.load(self.p.stage / "release-manifest.json")
        if manifest.get("status") != "PASS" or manifest.get("lane") != "FULL" or manifest.get("sourceCommit") != commit:
            raise ValueError("OCR requires the approved FULL manifest of this commit")
        ocr = manifest.get("financeOcr", {})
        artifacts = [a for a in manifest.get("artifacts", []) if a.get("name") == "finance-ocr"]
        if (len(artifacts) != 1 or artifacts[0].get("file") != "finance-ocr.zip" or
                ocr.get("sourceCommit") != commit or ocr.get("platform") != "linux-x86_64" or
                ocr.get("pythonMajorMinor") != "3.12"):
            raise ValueError("Missing same-commit Linux OCR contract")
        for key in ("bundleSha256", "bundleManifestSha256", "requirementsSha256"):
            if not SHA.fullmatch(str(ocr.get(key, ""))):
                raise ValueError("Invalid OCR digest contract")
        bundle = self.p.stage / "finance-ocr.zip"
        metadata = self.secure(bundle)
        if (not 1 <= metadata.st_size <= 1073741824 or metadata.st_size != artifacts[0].get("sizeBytes") or
                artifacts[0].get("sha256") != ocr["bundleSha256"] or digest(bundle) != ocr["bundleSha256"]):
            raise ValueError("OCR archive differs from the approved immutable artifact")
        return ocr

    def verifier(self):
        path = self.p.library / "finance_ocr_bundle.py"
        self.secure(path)
        spec = importlib.util.spec_from_file_location("installed_finance_ocr_bundle", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def trusted_service(self, candidate):
        template = self.p.library / SERVICE
        supplied = candidate / "deployment" / SERVICE
        self.secure(template)
        self.secure(supplied)
        if template.read_bytes() != supplied.read_bytes():
            raise ValueError("OCR unit differs from the fixed administrator-installed service policy")
        return template.read_bytes()

    def active(self, service):
        return self.command(["/usr/bin/systemctl", "is-active", "--quiet", service], allowed=(0, 3, 4)).returncode == 0

    def enabled(self):
        value = self.command(["/usr/bin/systemctl", "is-enabled", SERVICE], allowed=(0, 1, 3, 4)).stdout.strip()
        if value not in ("enabled", "disabled", "not-found", ""):
            raise ValueError("Unrecognized existing OCR unit enablement; administrator review required")
        return value == "enabled"

    def running_source(self, service, candidate):
        if not self.active(service):
            raise ValueError("The selected OCR unit is not active")
        if self.process_directory(service) != candidate / "app":
            raise ValueError("OCR process is not running from the selected source tree")

    def process_directory(self, service):
        main_pid = self.command(["/usr/bin/systemctl", "show", service, "--property=MainPID", "--value"]).stdout.strip()
        if main_pid == "0":
            return None
        if not re.fullmatch(r"[1-9][0-9]*", main_pid):
            raise ValueError("OCR process identity could not be verified")
        return Path("/proc", main_pid, "cwd").resolve(strict=True)

    def environment(self, token):
        return {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "PYTHONDONTWRITEBYTECODE": "1",
                "PYTHONUNBUFFERED": "1", "FINANCE_OCR_TOKEN": token}

    def wait(self, port):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_args, **_kwargs):
                raise ValueError("Private OCR health redirects are forbidden")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        for _ in range(75):
            try:
                with opener.open(f"http://127.0.0.1:{port}/health", timeout=2) as response:
                    if unique_json(response.read(4097)) == {"status": "READY", "provider": "RapidOCR"}:
                        return
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError("OCR did not become READY before the bounded deadline")

    def probe(self, candidate, port, token):
        result = self.command(["/usr/sbin/runuser", "-u", "hotelai", "--", str(candidate / "venv/bin/python"),
            str(self.p.library / "probe.py"), str(port)], env=self.environment(token), timeout=30)
        value = unique_json(result.stdout)
        if value.get("status") != "PASS" or value.get("syntheticOnly") is not True or not value.get("checks") or not all(v is True for v in value["checks"].values()):
            raise ValueError("Real synthetic inference did not pass")
        return value

    def runtime(self, candidate):
        # A regular release tree may contain the standard venv interpreter symlinks only.
        for directory, dirs, files in os.walk(candidate, followlinks=False):
            for name in dirs + files:
                path = Path(directory) / name
                info = path.lstat()
                if path.is_symlink():
                    interpreter = path.parent == candidate / "venv/bin" and name in ("python", "python3", "python3.12") and str(path.resolve()).startswith("/usr/bin/python3")
                    lib64 = path == candidate / "venv/lib64" and path.resolve() == candidate / "venv/lib"
                    if not (interpreter or lib64):
                        raise ValueError("Unexpected symlink in installed OCR runtime")
                    os.chown(path, 0, 0, follow_symlinks=False)
                    continue
                if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                    raise ValueError("Unexpected node in OCR runtime")
                os.chown(path, 0, 0)
                path.chmod(0o555 if stat.S_ISDIR(info.st_mode) or info.st_mode & 0o111 else 0o444)
        os.chown(candidate, 0, 0)
        candidate.chmod(0o555)

    def prepare(self, release_id, commit):
        candidate, state = self.ids(release_id, commit)
        if sys.version_info[:2] != (3, 12) or platform.system() != "Linux" or platform.machine() != "x86_64":
            raise ValueError("OCR release requires Linux x86_64 Python 3.12")
        ocr = self.approved(commit)
        for parent in (self.p.home, self.p.releases, self.p.state):
            self.directory(parent, 0o755 if parent != self.p.state else 0o700)
        self.directory(state)
        if candidate.exists() or candidate.is_symlink():
            raise ValueError("OCR release already exists; do not overwrite immutable candidates")
        verifier = self.verifier()
        verifier.extract_bundle(self.p.stage / "finance-ocr.zip", candidate, commit,
                                ocr["bundleManifestSha256"], ocr["requirementsSha256"])
        self.trusted_service(candidate)
        self.save(state / "record.json", {"releaseId": release_id, "sourceCommit": commit, "phase": "PREPARING", "identity": ocr})
        # The versioned venv is the only hotelai-writable directory during installation.
        candidate.chmod(0o755)
        for directory, dirs, files in os.walk(candidate):
            Path(directory).chmod(0o755)
        import pwd
        account = pwd.getpwnam("hotelai")
        venv = candidate / "venv"
        venv.mkdir(mode=0o700)
        os.chown(venv, account.pw_uid, account.pw_gid)
        self.command(["/usr/sbin/runuser", "-u", "hotelai", "--", "/usr/bin/python3", "-m", "venv", str(venv)], timeout=120)
        self.command(["/usr/sbin/runuser", "-u", "hotelai", "--", str(venv / "bin/python"), "-m", "pip",
                      "--isolated", "--disable-pip-version-check", "install", "--no-cache-dir", "--no-index", "--only-binary=:all:",
                      "--find-links", str(candidate / "wheels"), "--require-hashes", "-r", str(candidate / "app/requirements.lock")], timeout=600)
        self.command(["/usr/sbin/runuser", "-u", "hotelai", "--", str(venv / "bin/python"), "-m", "pip", "--isolated", "check"])
        self.runtime(candidate)
        token = token_from(self.p.worker_env.read_bytes()) if self.p.worker_env.exists() and self.private_environment(self.p.worker_env) else secrets.token_urlsafe(48)
        self.atomic(state / "candidate.env", ("FINANCE_OCR_TOKEN=" + token + "\n").encode(), mode=0o640, gid=account.pw_gid)
        unit = "hotel-ai-os-finance-ocr-prepare-" + release_id
        try:
            self.command(["/usr/bin/systemd-run", "--unit=" + unit, "--collect", "--service-type=exec",
                "--property=User=hotelai", "--property=Group=hotelai", "--property=NoNewPrivileges=true",
                "--property=ProtectSystem=strict", "--property=ProtectHome=true", "--property=PrivateTmp=true",
                "--property=MemoryMax=2G", "--property=TasksMax=128", "--property=IPAddressDeny=any",
                "--property=IPAddressAllow=127.0.0.1/32", "--property=EnvironmentFile=" + str(state / "candidate.env"),
                "--property=WorkingDirectory=" + str(candidate / "app"), "--setenv=PYTHONDONTWRITEBYTECODE=1",
                "--setenv=FINANCE_OCR_HOST=127.0.0.1", "--setenv=FINANCE_OCR_PORT=8096",
                "--setenv=FINANCE_OCR_TIMEOUT_SECONDS=12", "--setenv=FINANCE_OCR_STARTUP_TIMEOUT_SECONDS=120",
                str(venv / "bin/python"), "-m", "finance_ocr"])
            self.wait(8096)
            self.running_source(unit, candidate)
            proof = self.probe(candidate, 8096, token)
        finally:
            self.command(["/usr/bin/systemctl", "stop", unit], allowed=(0, 5))
        self.save(state / "prepare-proof.json", proof)
        self.save(state / "record.json", {"releaseId": release_id, "sourceCommit": commit, "phase": "PREPARED", "identity": ocr})
        self.save(self.p.stage / "finance-ocr.identity.json", {"releaseId": release_id, **ocr})

    def record(self, release_id, commit, phases):
        candidate, state = self.ids(release_id, commit)
        self.secure(state, directory=True)
        record = self.load(state / "record.json")
        if record.get("releaseId") != release_id or record.get("sourceCommit") != commit or record.get("phase") not in phases:
            raise ValueError("OCR release state does not match this operation")
        self.secure(candidate, directory=True)
        self.secure(candidate / "app/requirements.lock")
        if digest(candidate / "app/requirements.lock") != record["identity"]["requirementsSha256"]:
            raise ValueError("Installed requirements changed")
        return candidate, state, record

    def assert_prepared(self, release_id, commit):
        _, _, record = self.record(release_id, commit, ("PREPARED", "ACTIVE", "COMMITTED"))
        if record["identity"] != self.approved(commit) or self.load(self.p.stage / "finance-ocr.identity.json") != {"releaseId": release_id, **record["identity"]}:
            raise ValueError("OCR prepared identity differs from the FULL artifact")

    def link(self, target):
        self.secure(self.p.home, directory=True)
        temporary = self.p.home / (".current-" + secrets.token_hex(8))
        try:
            temporary.symlink_to(target, target_is_directory=True)
            os.replace(temporary, self.p.current)
        finally:
            if temporary.is_symlink():
                temporary.unlink()

    def current(self):
        if not self.p.current.exists() and not self.p.current.is_symlink():
            return None
        if not self.p.current.is_symlink():
            raise ValueError("OCR current must be a symlink")
        target = self.p.current.resolve(strict=True)
        if target.parent != self.p.releases or not RELEASE.fullmatch(target.name) or ".." in target.name:
            raise ValueError("OCR current escapes the immutable release root")
        self.secure(target, directory=True)
        return target

    def snapshot(self, state):
        result = {"previousTarget": str(self.current()) if self.current() else None,
                  "wasActive": self.active(SERVICE), "wasEnabled": self.enabled(), "files": {}}
        if result["wasActive"] and not result["previousTarget"]:
            raise ValueError("Active OCR lacks a versioned current identity")
        for name, path in (("core.env", self.p.core_env), ("worker.env", self.p.worker_env), ("worker.service", self.p.service_file)):
            if path.exists() or path.is_symlink():
                metadata = self.private_environment(path) if name.endswith(".env") else self.secure(path)
                self.atomic(state / ("previous-" + name), path.read_bytes())
                result["files"][name] = {"exists": True, "mode": stat.S_IMODE(metadata.st_mode),
                                          "gid": metadata.st_gid, "sha256": digest(path)}
            else:
                if name == "core.env":
                    raise ValueError("Existing Core configuration is required")
                result["files"][name] = {"exists": False}
        self.save(state / "rollback.json", result)
        return result

    def activate(self, release_id, commit):
        candidate, state, record = self.record(release_id, commit, ("PREPARED",))
        if self.active(CORE_SERVICE):
            raise ValueError("Stop Core through the FULL release flow before OCR activation")
        self.snapshot(state)
        token = token_from((state / "candidate.env").read_bytes())
        new_core = core_configuration((state / "previous-core.env").read_bytes(), token)
        trusted_service = self.trusted_service(candidate)
        self.atomic(state / "next-core.env", new_core)
        self.save(state / "planned-files.json", {"core.env": hashlib.sha256(new_core).hexdigest(),
            "worker.env": digest(state / "candidate.env"), "worker.service": hashlib.sha256(trusted_service).hexdigest()})
        self.save(state / "record.json", {**record, "phase": "ACTIVATING"})
        try:
            if self.active(SERVICE):
                self.command(["/usr/bin/systemctl", "stop", SERVICE])
            self.link(candidate)
            self.atomic(self.p.worker_env, (state / "candidate.env").read_bytes(), mode=0o640, gid=(state / "candidate.env").stat().st_gid)
            self.atomic(self.p.service_file, trusted_service, mode=0o644)
            self.command(["/usr/bin/systemctl", "daemon-reload"])
            self.command(["/usr/bin/systemctl", "enable", SERVICE])
            self.command(["/usr/bin/systemctl", "restart", SERVICE])
            self.wait(8095)
            self.probe(candidate, 8095, token)
            original = self.p.core_env.stat()
            if digest(self.p.core_env) != self.load(state / "rollback.json")["files"]["core.env"]["sha256"]:
                raise ValueError("Core configuration changed during OCR activation; administrator review required")
            self.atomic(self.p.core_env, new_core, mode=stat.S_IMODE(original.st_mode), gid=original.st_gid)
            self.save(state / "active-files.json", {"core.env": digest(self.p.core_env), "worker.env": digest(self.p.worker_env), "worker.service": digest(self.p.service_file)})
            self.save(state / "record.json", {**record, "phase": "ACTIVE"})
        except BaseException:
            self.rollback(release_id, commit)
            raise

    def rollback(self, release_id, commit):
        candidate, state, record = self.record(release_id, commit, ("PREPARED", "ACTIVATING", "ACTIVE", "ROLLED_BACK"))
        if record["phase"] == "ROLLED_BACK":
            return
        if record["phase"] == "PREPARED":
            # activate() durably journals ACTIVATING before its first active
            # mutation. A validation/snapshot failure before that point needs
            # no inverse operation. Do not turn this into a permissive reset:
            # contradictory live evidence must require administrator review.
            if self.current() == candidate or any((state / name).exists() or (state / name).is_symlink()
                    for name in ("active-files.json", "active-proof.json")):
                raise ValueError("PREPARED OCR release has activation traces; automatic recovery is unsafe")
            if self.active(SERVICE) and self.process_directory(SERVICE) == candidate / "app":
                raise ValueError("PREPARED OCR release is already running; automatic recovery is unsafe")
            snapshot_file = state / "rollback.json"
            if snapshot_file.exists() or snapshot_file.is_symlink():
                previous = self.load(snapshot_file)
                expected_target = Path(previous["previousTarget"]) if previous["previousTarget"] else None
                if self.current() != expected_target:
                    raise ValueError("Worker identity changed before activation; administrator review required")
                for name, path in (("core.env", self.p.core_env), ("worker.env", self.p.worker_env), ("worker.service", self.p.service_file)):
                    saved = previous["files"][name]
                    if saved["exists"]:
                        snapshot = state / ("previous-" + name)
                        self.secure(snapshot)
                        self.secure(path)
                        if digest(snapshot) != saved["sha256"] or digest(path) != saved["sha256"]:
                            raise ValueError("Configuration changed before activation; administrator review required")
                    elif path.exists() or path.is_symlink():
                        raise ValueError("Configuration appeared before activation; administrator review required")
            elif any((state / name).exists() or (state / name).is_symlink()
                     for name in ("planned-files.json", "next-core.env")):
                raise ValueError("Prepared activation plan has no valid rollback snapshot")
            return
        previous = self.load(state / "rollback.json")
        planned = self.load(state / "planned-files.json")
        # Reject an unrelated later deployment, rather than overwriting its configuration.
        now = self.current()
        if now not in (self.p.releases / release_id, Path(previous["previousTarget"]) if previous["previousTarget"] else None):
            raise ValueError("Another OCR release is active; automatic rollback is unsafe")
        # Verify *all* snapshots and current file identities before stopping a service.
        # ACTIVATING may have stopped between any two atomic writes, so each file
        # must still be either its exact saved value or its exact planned value.
        for name, path in (("core.env", self.p.core_env), ("worker.env", self.p.worker_env), ("worker.service", self.p.service_file)):
            saved = previous["files"][name]
            allowed = {planned[name]}
            if saved["exists"]:
                snapshot = state / ("previous-" + name)
                self.secure(snapshot)
                if digest(snapshot) != saved["sha256"]:
                    raise ValueError("Protected rollback snapshot changed")
                allowed.add(saved["sha256"])
            if path.exists() or path.is_symlink():
                self.secure(path)
                if digest(path) not in allowed:
                    raise ValueError("OCR configuration changed after activation; administrator recovery required")
            elif saved["exists"]:
                raise ValueError("Protected OCR configuration disappeared; administrator recovery required")
        if record["phase"] == "ACTIVE":
            actual = self.load(state / "active-files.json")
            for name, path in (("core.env", self.p.core_env), ("worker.env", self.p.worker_env), ("worker.service", self.p.service_file)):
                self.secure(path)
                if digest(path) != actual[name]:
                    raise ValueError("OCR configuration changed after activation; administrator recovery required")
        self.command(["/usr/bin/systemctl", "stop", SERVICE], allowed=(0, 5))
        for name, path in (("core.env", self.p.core_env), ("worker.env", self.p.worker_env), ("worker.service", self.p.service_file)):
            saved = previous["files"][name]
            if saved["exists"]:
                snapshot = state / ("previous-" + name)
                self.atomic(path, snapshot.read_bytes(), mode=saved["mode"], gid=saved["gid"])
            elif path.exists() or path.is_symlink():
                self.secure(path)
                path.unlink()
        if previous["previousTarget"]:
            self.link(Path(previous["previousTarget"]))
        elif self.p.current.is_symlink():
            self.p.current.unlink()
        self.command(["/usr/bin/systemctl", "daemon-reload"])
        self.command(["/usr/bin/systemctl", "enable" if previous["wasEnabled"] else "disable", SERVICE], allowed=(0, 1) if not previous["files"]["worker.service"]["exists"] else (0,))
        if previous["wasActive"]:
            self.command(["/usr/bin/systemctl", "start", SERVICE])
            self.wait(8095)
            self.probe(Path(previous["previousTarget"]), 8095, token_from(self.p.worker_env.read_bytes()))
        self.save(state / "record.json", {**record, "phase": "ROLLED_BACK"})

    def verify(self, release_id, commit):
        candidate, state, record = self.record(release_id, commit, ("ACTIVE", "COMMITTED"))
        installed_identity = self.load(self.p.root / "opt/hotel-ai-os/current/.finance-ocr.json")
        if installed_identity != {"releaseId": release_id, **record["identity"]}:
            raise ValueError("Core release OCR identity differs from the active worker")
        if self.current() != candidate or not self.active(SERVICE) or not self.enabled():
            raise ValueError("OCR is not running the selected immutable release")
        verifier = self.verifier()
        manifest = self.load(candidate / "bundle-manifest.json")
        if digest(candidate / "bundle-manifest.json") != record["identity"]["bundleManifestSha256"] or manifest.get("sourceCommit") != commit:
            raise ValueError("Active OCR manifest identity changed")
        for entry in manifest["files"]:
            path = candidate / verifier.safe_path(entry["file"])
            self.secure(path)
            if path.stat().st_size != entry["sizeBytes"] or digest(path) != entry["sha256"]:
                raise ValueError("Active OCR source/wheel bytes changed")
        self.private_environment(self.p.worker_env)
        self.private_environment(self.p.core_env)
        token = token_from(self.p.worker_env.read_bytes())
        core = self.p.core_env.read_bytes()
        if core_configuration(core, token) != core:
            raise ValueError("Core OCR endpoint/token/timeout differ from the running worker")
        self.running_source(SERVICE, candidate)
        sockets = self.command(["/usr/bin/ss", "-H", "-lnt", "sport = :8095"]).stdout.splitlines()
        if len(sockets) != 1 or len(sockets[0].split()) < 5 or sockets[0].split()[3] != "127.0.0.1:8095":
            raise ValueError("OCR port must listen only on IPv4 loopback")
        self.wait(8095)
        proof = self.probe(candidate, 8095, token)
        self.save(state / "active-proof.json", {"sourceCommit": commit, "releaseId": release_id, **proof})

    def commit(self, release_id, commit):
        self.verify(release_id, commit)
        _, state, record = self.record(release_id, commit, ("ACTIVE", "COMMITTED"))
        self.save(state / "record.json", {**record, "phase": "COMMITTED"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("PREPARE", "ASSERT_PREPARED", "ACTIVATE", "VERIFY", "ROLLBACK", "COMMIT"))
    parser.add_argument("release_id")
    parser.add_argument("source_commit")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError("OCR deployment must be invoked by the trusted root FULL coordinator")
    def interrupted(_signum, _frame):
        raise InterruptedError("OCR deployment interrupted")
    for name in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(name, interrupted)
    import fcntl
    with open("/run/lock/hotel-ai-os-finance-ocr-release.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        getattr(Deployment(), args.action.lower())(args.release_id, args.source_commit)
    print("OCR_RELEASE_" + args.action + "_OK sourceCommit=" + args.source_commit + " releaseId=" + args.release_id)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Explicit, bounded own validation reasons only; never include subprocess output.
        message = str(error) if type(error) is ValueError else type(error).__name__
        print("OCR_RELEASE_ERROR " + message, file=sys.stderr)
        sys.exit(1)
