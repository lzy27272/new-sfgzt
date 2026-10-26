#!/usr/bin/env python3
"""Fixed FULL-release verification. Never execute source from the uploaded archive as root."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import zipfile

HEX = re.compile(r'[a-f0-9]{64}')
COMMIT = re.compile(r'[a-f0-9]{40}')
FILES = {'deployment-plan.json', 'release-manifest.json', 'web.zip', 'core-api.jar', 'run-migration-once.sh', 'finance-ocr.zip'}
ARTIFACTS = {'web': 'web.zip', 'core': 'core-api.jar', 'migration-runner': 'run-migration-once.sh', 'finance-ocr': 'finance-ocr.zip'}
MAX_TOTAL = 3 * 1024**3
RESTORE_RESERVE = 4 * 1024**3

def account(name):
    import pwd
    return pwd.getpwnam(name)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result

def json_bytes(raw):
    return json.loads(raw.decode('utf-8-sig'), object_pairs_hook=unique)

def safe_name(name):
    p = PurePosixPath(name)
    return bool(name) and not name.startswith('/') and '\\' not in name and ':' not in name and all(part not in ('', '.', '..') for part in name.rstrip('/').split('/')) and not any(ord(c) < 32 or ord(c) == 127 for c in name) and not p.is_absolute()

def entries(archive, maximum=MAX_TOTAL):
    rows = archive.infolist()
    if not rows or len(rows) > 20000:
        raise ValueError('Invalid archive count')
    seen, total = set(), 0
    for entry in rows:
        if not safe_name(entry.filename) or entry.filename in seen or entry.flag_bits & 1 or stat.S_IFMT(entry.external_attr >> 16) not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise ValueError('Unsafe archive member')
        seen.add(entry.filename)
        total += entry.file_size
        if total > maximum or entry.file_size < 0:
            raise ValueError('Archive size limit')
    return rows

def verify(archive_path, digest, commit, classifier):
    if not HEX.fullmatch(digest) or not COMMIT.fullmatch(commit) or not HEX.fullmatch(classifier):
        raise ValueError('Invalid approved identity')
    with Path(archive_path).open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
            raise ValueError('Approved CI artifact digest differs')
    with zipfile.ZipFile(archive_path) as archive:
        rows = entries(archive)
        if {row.filename for row in rows} != FILES or any(row.is_dir() for row in rows):
            raise ValueError('FULL artifact requires exact closed file set')
        raw_plan, raw_manifest = archive.read('deployment-plan.json'), archive.read('release-manifest.json')
        if len(raw_plan) > 5*1024**2 or len(raw_manifest) > 1024**2:
            raise ValueError('Release metadata size limit')
        plan, manifest = json_bytes(raw_plan), json_bytes(raw_manifest)
        for document in (plan, manifest):
            if document.get('schemaVersion') != 1 or document.get('status') != 'PASS' or document.get('lane') != 'FULL':
                raise ValueError('Approved FULL metadata required')
        if plan.get('headRef') != commit or not COMMIT.fullmatch(str(plan.get('baseRef', ''))) or plan.get('policyVersion') != 3 or plan.get('classifierSha256') != classifier:
            raise ValueError('Plan identity differs')
        if any(plan.get(key) is not True for key in ('requiresWeb', 'requiresCore', 'requiresDatabaseBackup', 'requiresMigrationGate', 'requiresFullRegression')):
            raise ValueError('FULL gates cannot be disabled')
        if manifest.get('sourceCommit') != commit or manifest.get('policy') != {'version': 3, 'classifierSha256': classifier} or manifest.get('planSha256') != sha(raw_plan):
            raise ValueError('Manifest source/policy differs')
        if manifest.get('safeguards') != {'deployRequiresEnvironmentApproval': True, 'fullLaneAutoDeployAllowed': False, 'sourceCommitBound': True, 'hashesRequired': True}:
            raise ValueError('Manual environment approval is mandatory')
        artifacts = manifest.get('artifacts', [])
        if len(artifacts) != len(ARTIFACTS) or {row.get('name') for row in artifacts} != set(ARTIFACTS):
            raise ValueError('FULL artifact descriptors differ')
        descriptors = {}
        for row in artifacts:
            file = ARTIFACTS[row['name']]
            if row.get('file') != file or not HEX.fullmatch(str(row.get('sha256', ''))):
                raise ValueError('Artifact descriptor differs')
            entry = archive.getinfo(file)
            with archive.open(entry) as stream:
                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if row.get('sizeBytes') != entry.file_size or entry.file_size <= 0 or row['sha256'] != actual:
                raise ValueError('Artifact bytes/hash differ')
            descriptors[file] = row
        if manifest.get('core', {}).get('jarSha256') != descriptors['core-api.jar']['sha256'] or manifest.get('web', {}).get('bundleSha256') != descriptors['web.zip']['sha256']:
            raise ValueError('Component descriptor differs')
        ocr = manifest.get('financeOcr', {})
        if ocr.get('sourceCommit') != commit or ocr.get('bundleSha256') != descriptors['finance-ocr.zip']['sha256'] or ocr.get('pythonMajorMinor') != '3.12' or ocr.get('platform') != 'linux-x86_64' or not HEX.fullmatch(str(ocr.get('bundleManifestSha256', ''))) or not HEX.fullmatch(str(ocr.get('requirementsSha256', ''))):
            raise ValueError('Same-version Linux OCR artifact required')
        # Validate complete web bytes before extracting any public tree.
        with zipfile.ZipFile(io.BytesIO(archive.read('web.zip'))) as web:
            web_rows = entries(web, 1024**3)
            lines, names = [], set()
            for row in web_rows:
                if row.is_dir():
                    continue
                if row.filename in ('.source-commit', '.release-id'):
                    raise ValueError('Reserved web identity')
                names.add(row.filename)
                with web.open(row) as stream:
                    value = hashlib.file_digest(stream, 'sha256').hexdigest()
                lines.append((row.filename, row.file_size, value))
            if 'index.html' not in names or not any(name.startswith('assets/') for name in names):
                raise ValueError('Web entry/assets missing')
            tree = '\n'.join(f'{name}:{size}:{value}' for name, size, value in sorted(lines, key=lambda row: row[0].encode('utf-16-be')))
            if sha(tree.encode()) != manifest['web'].get('treeSha256') or sha(web.read('index.html')) != manifest['web'].get('indexSha256') or manifest['web'].get('treeFingerprintAlgorithm') != 'sha256(sorted-path-colon-size-colon-content-sha256-v1)':
                raise ValueError('Web tree differs')
        # Only libraries + exact SQL migrations enter the Flyway-only runtime, never application classes.
        with zipfile.ZipFile(io.BytesIO(archive.read('core-api.jar'))) as jar:
            jar_rows = entries(jar)
            migrations, versions = [], set()
            for row in jar_rows:
                match = re.fullmatch(r'BOOT-INF/classes/db/migration/V([0-9]+)__[^/]+\.sql', row.filename)
                if match:
                    version = int(match[1])
                    if version in versions:
                        raise ValueError('Duplicate migration version')
                    versions.add(version); migrations.append((row.filename, sha(jar.read(row))))
            migration = manifest.get('migration', {})
            fingerprint = sha('\n'.join(f'{name}:{digest}' for name, digest in sorted(migrations, key=lambda row: row[0].encode('utf-16-be'))).encode())
            if not migrations or migration.get('version') != str(max(versions)) or migration.get('fingerprint') != fingerprint or migration.get('migrationCount') != len(migrations) or migration.get('algorithm') != 'sha256(sorted-entry-name-colon-content-sha256-v1)':
                raise ValueError('Migration identity differs')
        return manifest

def copy_private_artifact(source, target, before):
    remaining = before.st_size
    if not 0 < remaining <= MAX_TOTAL:
        raise ValueError('Incoming artifact size limit')
    while remaining:
        block = source.read(min(1024**2, remaining))
        if not block or len(block) > remaining:
            raise ValueError('Incoming artifact shortened during private copy')
        target.write(block)
        remaining -= len(block)
    if source.read(1):
        raise ValueError('Incoming artifact grew during private copy')
    after = os.fstat(source.fileno())
    fields = ('st_size', 'st_mtime_ns', 'st_ctime_ns', 'st_ino', 'st_dev', 'st_mode', 'st_uid', 'st_gid', 'st_nlink')
    if any(getattr(before, field) != getattr(after, field) for field in fields):
        raise ValueError('Incoming artifact changed during private copy')
    target.flush(); os.fsync(target.fileno())

def snapshot(incoming, destination, digest, commit, classifier):
    incoming, destination = Path(incoming), Path(destination)
    owner = account('hotel-ai-deploy').pw_uid
    parent = incoming.parent
    info = parent.lstat()
    if parent != Path('/tmp/hotel-ai-os-full-ci-incoming') or not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o007:
        raise ValueError('Unsafe fixed incoming parent')
    flags = os.O_RDONLY | os.O_NOFOLLOW
    with _directory(incoming) as directory:
        meta = os.fstat(directory)
        if meta.st_uid != owner or stat.S_IMODE(meta.st_mode) != 0o700:
            raise ValueError('Unsafe incoming directory')
        if set(os.listdir(directory)) != {'release-artifact.zip'}:
            raise ValueError('Unexpected incoming files')
        descriptor = os.open('release-artifact.zip', flags, dir_fd=directory)
        with os.fdopen(descriptor, 'rb') as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_uid != owner or before.st_nlink != 1 or before.st_mode & 0o077 or not 0 < before.st_size <= MAX_TOTAL:
                raise ValueError('Unsafe incoming artifact')
            destination.mkdir(mode=0o700)
            archive = destination / 'release-artifact.zip'
            with archive.open('xb') as target:
                copy_private_artifact(source, target, before)
    manifest = verify(archive, digest, commit, classifier)
    with zipfile.ZipFile(archive) as package:
        for name in FILES:
            with package.open(name) as source, (destination / name).open('xb') as target:
                while chunk := source.read(1024**2):
                    target.write(chunk)
        with zipfile.ZipFile(destination / 'web.zip') as web:
            (destination / 'web').mkdir(mode=0o700)
            for row in web.infolist():
                target = destination / 'web' / row.filename
                if row.is_dir():
                    target.mkdir(parents=True, exist_ok=True, mode=0o700)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with web.open(row) as source, target.open('xb') as output:
                        while chunk := source.read(1024**2): output.write(chunk)
    return manifest

class _directory:
    def __init__(self, path): self.path = path
    def __enter__(self): self.fd = os.open(self.path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW); return self.fd
    def __exit__(self, *_args): os.close(self.fd)

def runtime(jar_path, destination):
    destination = Path(destination); destination.mkdir(mode=0o750)
    group = account('hotelai').pw_gid
    os.chown(destination, 0, group)
    with zipfile.ZipFile(jar_path) as jar:
        for row in entries(jar):
            if not (re.fullmatch(r'BOOT-INF/lib/[^/]+\.jar', row.filename) or re.fullmatch(r'BOOT-INF/classes/db/migration/V[0-9]+__[^/]+\.sql', row.filename)):
                continue
            relative = row.filename.removeprefix('BOOT-INF/')
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
            with jar.open(row) as source, target.open('xb') as output:
                while chunk := source.read(1024**2): output.write(chunk)
    for path in (destination, *destination.rglob('*')):
        os.chown(path, 0, group); path.chmod(0o750 if path.is_dir() else 0o640)

def staged_state_bytes(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Actual protected snapshot tree is required')
    total, pending = 0, [root]
    device = root.stat().st_dev
    while pending:
        directory = pending.pop()
        with os.scandir(directory) as children:
            for entry in children:
                info = Path(entry.path).lstat()
                if info.st_dev != device or not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                    raise ValueError('Unsupported staged snapshot entry')
                if stat.S_ISDIR(info.st_mode): pending.append(Path(entry.path))
                else: total += info.st_size
    if total <= 0:
        raise ValueError('Empty snapshot cannot size the paired restore')
    return total

def restore_space_plan(state_bytes, database_bytes, restore_filesystem, database_filesystems):
    if type(state_bytes) is not int or type(database_bytes) is not int or state_bytes <= 0 or database_bytes <= 0 or not database_filesystems:
        raise ValueError('Actual positive state/database sizes are required')
    devices = {}
    for role, filesystem in [('RESTORE_WORK', restore_filesystem), *(('DATABASE_STORAGE', row) for row in database_filesystems)]:
        device, available = filesystem['device'], filesystem['availableBytes']
        if type(device) is not int or type(available) is not int or available < 0:
            raise ValueError('Invalid actual filesystem capacity')
        row = devices.setdefault(device, {'device': device, 'availableBytes': available, 'roles': []})
        row['availableBytes'] = min(row['availableBytes'], available)
        if role not in row['roles']: row['roles'].append(role)
    for row in devices.values():
        # Staging and encrypted backups already occupy disk; only additional recovery peaks belong here.
        peak = (5*state_bytes if 'RESTORE_WORK' in row['roles'] else 0) + (8*database_bytes if 'DATABASE_STORAGE' in row['roles'] else 0)
        row.update(peakBytes=peak, reserveBytes=RESTORE_RESERVE, requiredBytes=peak+RESTORE_RESERVE)
        row['status'] = 'PASS' if row['availableBytes'] >= row['requiredBytes'] else 'INSUFFICIENT'
    rows = sorted(devices.values(), key=lambda row: row['device'])
    return {'status': 'PASS' if all(row['status']=='PASS' for row in rows) else 'INSUFFICIENT', 'gate': 'PAIRED_RESTORE_AND_CLONE',
            'stateBytes': state_bytes, 'databaseBytes': database_bytes, 'formula': '5*S on restore FS + 8*D on each database storage FS + 4GiB per distinct FS', 'filesystems': rows}

def actual_filesystem(path):
    resolved = Path(path).resolve(strict=True)
    if not resolved.is_dir(): raise ValueError('Actual recovery filesystem is required')
    info, capacity = resolved.stat(), os.statvfs(resolved)
    return {'device': info.st_dev, 'availableBytes': capacity.f_bavail*capacity.f_frsize}

def selected_snapshot_bytes(snapshot, snapshot_id):
    if not isinstance(snapshot_id,str) or not re.fullmatch(r'[0-9]{8}T[0-9]{6}[+-][0-9]{4}',snapshot_id):
        raise ValueError('Strict selected snapshot identity is required')
    snapshot = Path(snapshot)
    manifest = snapshot/'var/lib/hotel-ai-os/backup-core-originals-manifest.json'
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size>128*1024**2:
        raise ValueError('Actual selected original snapshot manifest is required')
    metadata = json_bytes(manifest.read_bytes())
    if metadata.get('schemaVersion')!=1 or metadata.get('bundleType')!='CORE_AND_OTA_STATE_WITH_ORIGINALS' or metadata.get('snapshotId')!=snapshot_id:
        raise ValueError('Staging does not belong to the selected successful backup')
    return staged_state_bytes(snapshot)

def successful_backup_state_bytes(backup_log,snapshot_id,database,index):
    path = Path(backup_log)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>128*1024**2:
        raise ValueError('Bounded successful backup metadata is required')
    matches=[]
    fields={'stateBytes','indexSha256','databaseSha256'}
    for line in path.read_bytes().splitlines():
        if not line.lstrip().startswith(b'{'): continue
        try: record=json_bytes(line)
        except (ValueError,UnicodeError): continue
        if not isinstance(record,dict) or record.get('snapshotId')!=snapshot_id or not fields.intersection(record): continue
        if not fields.issubset(record) or type(record['stateBytes']) is not int or record['stateBytes']<=0 or any(not HEX.fullmatch(str(record[name])) for name in ('indexSha256','databaseSha256')):
            raise ValueError('Malformed successful selected snapshot proof')
        matches.append(record)
    if len(matches)!=1:
        raise ValueError('Exactly one successful selected snapshot proof is required')
    record=matches[0]
    for file,key in ((database,'databaseSha256'),(index,'indexSha256')):
        file=Path(file)
        if file.is_symlink() or not file.is_file(): raise ValueError('Selected backup must be a regular immutable archive')
        with file.open('rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest()!=record[key]:
                raise ValueError('Selected backup no longer matches the successful snapshot proof')
    return record['stateBytes']

def restore_capacity(snapshot_id,backup_log):
    log=Path(backup_log)
    if log.name!='backup.log' or log.parent.parent!=Path('/var/lib/hotel-ai-os-deploy/full-ci') or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}',log.parent.name) or '..' in log.parent.name or log.resolve(strict=True)!=log:
        raise ValueError('Fixed private FULL backup evidence is required')
    info=log.stat()
    parent=log.parent.stat()
    if info.st_uid!=0 or info.st_gid!=0 or info.st_nlink!=1 or info.st_mode & 0o077 or parent.st_uid!=0 or parent.st_gid!=0 or parent.st_mode & 0o077:
        raise ValueError('Backup proof must remain root-private')
    snapshot = Path('/var/backups/sifangguan-ota/staging/snapshot')
    if snapshot.resolve(strict=True) != snapshot or snapshot.stat().st_uid != 0 or stat.S_IMODE(snapshot.stat().st_mode) != 0o700:
        raise ValueError('Snapshot must remain protected in its fixed root-owned staging path')
    staged_bytes = selected_snapshot_bytes(snapshot,snapshot_id)
    selected_bytes = successful_backup_state_bytes(log,snapshot_id,
        Path('/var/backups/hotel-ai-os/postgres/daily')/f'hotel_ai_os-daily-{snapshot_id}.dump.enc',
        Path('/var/backups/sifangguan-ota/state/daily')/f'sifangguan-ota-state-daily-{snapshot_id}.snapshot.json.enc')
    state_bytes = max(selected_bytes,staged_bytes)
    query = """select jsonb_build_object('databaseBytes',pg_database_size(current_database()),'locations',
      jsonb_build_array(current_setting('data_directory'),current_setting('data_directory')||'/pg_wal') ||
      (select coalesce(jsonb_agg(pg_tablespace_location(oid)),'[]'::jsonb) from pg_tablespace
       where oid in (select dattablespace from pg_database where datname=current_database()
         union select distinct reltablespace from pg_class where reltablespace<>0)
       and pg_tablespace_location(oid)<>''))"""
    result = subprocess.run(['/usr/bin/sudo','-u','postgres','/usr/bin/psql','-XAtq','--set','ON_ERROR_STOP=1','--dbname','hotel_ai_os','--command',query], check=True, text=True, capture_output=True, timeout=30)
    database = json_bytes(result.stdout.encode())
    locations = database.get('locations')
    if not isinstance(locations,list) or len(locations)<2 or any(not isinstance(path,str) or not path.startswith('/') for path in locations):
        raise ValueError('Actual PostgreSQL storage locations are required')
    plan=restore_space_plan(state_bytes,database['databaseBytes'],actual_filesystem('/var/backups/hotel-ai-os/postgres'),[actual_filesystem(path) for path in locations])
    plan.update(snapshotId=snapshot_id,selectedStateBytes=selected_bytes,stagedStateBytes=staged_bytes)
    return plan

def flyway(runtime_root, database, mode):
    if database != 'hotel_ai_os' and not re.fullmatch(r'hotel_ai_os_full_ci_[0-9]+_[0-9]+', database):
        raise ValueError('Uncontrolled database name')
    if mode not in ('MIGRATE', 'VALIDATE'):
        raise ValueError('Uncontrolled Flyway operation')
    env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}
    for raw in Path('/etc/hotel-ai-os/migration.env').read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#') or '=' not in line: continue
        name, value = line.split('=', 1)
        if name not in ('DB_MIGRATION_USERNAME', 'DB_MIGRATION_PASSWORD'): continue
        if name in env: raise ValueError('Duplicate migration credential')
        if len(value) > 1 and value[0] == value[-1] and value[0] in ('"', "'"): value = value[1:-1]
        env[name] = value
    if env.get('DB_MIGRATION_USERNAME') != 'hotel_ai_os_owner' or not env.get('DB_MIGRATION_PASSWORD'):
        raise ValueError('Actual restricted migration credentials required')
    root = Path(runtime_root)
    if root.parent != Path('/run/hotel-ai-os-full-ci') or root.is_symlink(): raise ValueError('Unsafe private Flyway runtime')
    source = Path('/usr/local/lib/hotel-ai-os/bootstrap/FullCiFlyway.java')
    classes = root / 'trusted-classes'; classes.mkdir(mode=0o750, exist_ok=True)
    subprocess.run(['/usr/bin/javac', '-proc:none', '--release', '21', '-cp', str(root / 'lib/*'), '-d', str(classes), str(source)], check=True, timeout=60)
    identity = account('hotelai')
    for path in (classes, *classes.rglob('*')):
        os.chown(path, 0, identity.pw_gid); path.chmod(0o750 if path.is_dir() else 0o640)
    subprocess.run(['/usr/bin/java', '-Xmx768m', '-cp', f'{classes}:{root}/lib/*', 'FullCiFlyway', database, str(root / 'classes/db/migration'), mode], env=env, user=identity.pw_uid, group=identity.pw_gid, extra_groups=[], check=True, timeout=300)

def main():
    parser = argparse.ArgumentParser(); sub = parser.add_subparsers(dest='action', required=True)
    for action in ('verify', 'snapshot'):
        command = sub.add_parser(action)
        command.add_argument('--archive' if action == 'verify' else '--incoming', required=True)
        if action == 'snapshot': command.add_argument('--destination', required=True)
        for key in ('digest', 'commit', 'classifier'): command.add_argument('--'+key, required=True)
    command = sub.add_parser('runtime'); command.add_argument('jar'); command.add_argument('destination')
    command = sub.add_parser('flyway'); command.add_argument('root'); command.add_argument('database'); command.add_argument('mode')
    command = sub.add_parser('restore-capacity'); command.add_argument('--snapshot',required=True); command.add_argument('--backup-log',required=True)
    args = parser.parse_args()
    if args.action == 'verify': result = verify(args.archive, args.digest, args.commit, args.classifier)
    elif args.action == 'snapshot': result = snapshot(args.incoming, args.destination, args.digest, args.commit, args.classifier)
    elif args.action == 'runtime': runtime(args.jar, args.destination); result = {'runtime': 'SQL_AND_LIBRARIES_ONLY'}
    elif args.action == 'restore-capacity': result = restore_capacity(args.snapshot,args.backup_log)
    else: flyway(args.root, args.database, args.mode); result = {'flyway': 'PASS'}
    print(json.dumps(result, separators=(',', ':')))
    if result.get('status') == 'INSUFFICIENT': raise SystemExit(3)

if __name__ == '__main__':
    try: main()
    except Exception:
        raise SystemExit('FULL_CI_VERIFICATION_FAILED; inspect private server evidence, no secret details are printed') from None
