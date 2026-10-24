#!/usr/bin/env python3
"""Verify the paired DB/originals recovery point, never restoring into live paths."""
from __future__ import annotations
import hashlib
import json
import os
import re
import stat
import sys
import tarfile
from pathlib import Path, PurePosixPath

LIVE = Path('/var/lib/hotel-ai-os/attachments')
STAMP = re.compile(r'[0-9]{8}T[0-9]{6}[+-][0-9]{4}')

def fail(message):
    raise ValueError(message)

def safe_relative(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or any(p in ('', '.', '..') for p in value.split('/')) or '\\' in value or any(ord(c)<32 for c in value):
        fail('Unsafe original object path')
    return path.as_posix()

def no_links(path):
    path = Path(path)
    for parent in [path, *path.parents]:
        if parent.is_symlink(): fail('Symlink is not a backup root or original')
    return path

def runtime_settings(pid):
    if not re.fullmatch(r'[1-9][0-9]*',str(pid)): fail('Invalid Core API process identity')
    allowed={'ATTACHMENT_STORAGE_ROOT','FINANCE_ATTACHMENTS_ROOT','FINANCE_BUSINESS_ATTACHMENTS_ROOT','ACADEMY_STORAGE_ROOT'}
    actual={}
    for entry in Path('/proc',str(pid),'environ').read_bytes().split(b'\0'):
        key,sep,value=entry.partition(b'=')
        if sep and key.decode('utf-8','strict') in allowed: actual[key.decode()]=value.decode('utf-8','strict')
    # Systemd's normal command line fixes only server address/port. Do not miss an unreviewed CLI root override.
    for entry in Path('/proc',str(pid),'cmdline').read_bytes().split(b'\0'):
        if entry.startswith((b'--app.attachments.root',b'--app.finance.attachments-root',b'--app.finance.business-attachments-root',b'--app.academy.storage-root')):
            fail('Core API command-line storage override needs explicit backup configuration reconciliation')
    return actual

def settings(env,pid=None):
    values = {}
    for line in Path(env).read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'): continue
        key, sep, value = line.partition('=')
        if sep and key in ('ATTACHMENT_STORAGE_ROOT','FINANCE_ATTACHMENTS_ROOT','FINANCE_BUSINESS_ATTACHMENTS_ROOT','ACADEMY_STORAGE_ROOT'):
            if key in values: fail('Duplicate original storage configuration')
            if len(value)>1 and value[0] in ('"', "'") and value[-1]==value[0]: value=value[1:-1]
            values[key]=value
    if pid is not None and str(pid)!='0': values.update(runtime_settings(pid))
    # Match the application's real default, which is intentionally unsafe for a production release directory.
    # Production must explicitly configure the stable volume; absence must not be reported as coverage.
    root = no_links(Path(values.get('ATTACHMENT_STORAGE_ROOT','var/attachments')))
    if root != LIVE or not root.is_dir(): fail('Core originals must use the real stable /var/lib/hotel-ai-os/attachments directory')
    device=root.stat().st_dev
    for directory,dirs,leaves in os.walk(root,followlinks=False):
        for name in dirs+leaves:
            node=Path(directory)/name;meta=node.lstat()
            if node.is_symlink() or meta.st_dev!=device or not(stat.S_ISREG(meta.st_mode) or stat.S_ISDIR(meta.st_mode)): fail('Original tree contains links, special files or an uncovered nested filesystem')
    result={'root':str(root)}
    for key, default, name in [('FINANCE_ATTACHMENTS_ROOT',root/'finance-originals','financeRelative'),('FINANCE_BUSINESS_ATTACHMENTS_ROOT',root/'finance-business-originals','businessRelative'),('ACADEMY_STORAGE_ROOT',root/'academy','academyRelative')]:
        path=no_links(Path(values.get(key,str(default))))
        if not path.is_absolute() or '..' in path.parts or not path.is_relative_to(root): fail('Configured finance/academy original root is outside the backed-up stable tree')
        result[name]=safe_relative(path.relative_to(root).as_posix())
    return result

def digest(path):
    before=path.lstat()
    if not stat.S_ISREG(before.st_mode) or path.is_symlink(): fail('Original is not a regular file')
    with path.open('rb') as stream:
        value=hashlib.file_digest(stream,'sha256').hexdigest()
    after=path.lstat()
    if (before.st_size,before.st_mtime_ns,before.st_ino)!=(after.st_size,after.st_mtime_ns,after.st_ino): fail('Original changed during frozen capture')
    return {'bytes':after.st_size,'sha256':value}

def files(root):
    no_links(root)
    if not root.is_dir(): fail('Missing isolated original tree')
    result={}
    for directory, dirs, leaves in os.walk(root,followlinks=False):
        for name in dirs:
            node=Path(directory)/name
            if node.is_symlink() or not node.is_dir(): fail('Unsafe node in original tree')
        for name in leaves:
            node=Path(directory)/name
            result[safe_relative(node.relative_to(root).as_posix())]=digest(node)
    return dict(sorted(result.items()))

def references(rows, config, tree):
    if not isinstance(rows,list): fail('Actual database references must be a list')
    for row in rows:
        key=safe_relative(str(row['object_key']))
        if row['domain'] in ('FINANCE','FINANCE_BUSINESS','ACADEMY'):
            key=safe_relative(config[{'FINANCE':'financeRelative','FINANCE_BUSINESS':'businessRelative','ACADEMY':'academyRelative'}[row['domain']]]+'/'+key)
        if key not in tree: fail('Referenced original is absent from the paired snapshot')
        record=tree[key]
        if row.get('size_bytes') is None or int(row['size_bytes'])!=record['bytes']: fail('Referenced original byte count differs from database')
        sha=row.get('sha256')
        if not isinstance(sha,str) or not re.fullmatch(r'[0-9a-f]{64}',sha) or sha!=record['sha256']: fail('Referenced original hash is missing or differs from database')
    return rows

def capture(root, output, snapshot, config, refs):
    if not STAMP.fullmatch(snapshot): fail('Invalid paired snapshot identity')
    tree=files(root); references(refs,config,tree)
    data={'schemaVersion':1,'bundleType':'CORE_AND_OTA_STATE_WITH_ORIGINALS','snapshotId':snapshot,'configuration':config,'files':tree,'references':refs}
    Path(output).write_text(json.dumps(data,sort_keys=True,separators=(',',':')),encoding='utf-8')
    return data

def verify(root, manifest, snapshot, refs):
    data=json.loads(Path(manifest).read_text(encoding='utf-8'))
    if data.get('schemaVersion')!=1 or data.get('bundleType')!='CORE_AND_OTA_STATE_WITH_ORIGINALS' or data.get('snapshotId')!=snapshot or not STAMP.fullmatch(snapshot): fail('Wrong database/original pair or manifest schema')
    tree=files(root)
    if tree!=data.get('files'): fail('Original files differ from the frozen manifest')
    references(refs,data['configuration'],tree)
    if refs!=data.get('references'): fail('Restored database original index differs from frozen capture')
    return {'restore':'PASS','snapshotId':snapshot,'originalFiles':len(tree),'databaseReferences':len(refs),'manifestSha256':digest(Path(manifest))['sha256']}

def extract(archive, destination):
    destination=no_links(Path(destination))
    if not destination.is_dir() or any(destination.iterdir()): fail('Restore extraction requires an existing empty isolated directory')
    with tarfile.open(archive,'r:gz') as source:
        for entry in source.getmembers():
            name=entry.name.removesuffix('/')
            safe_relative(name)
            if not (entry.isfile() or entry.isdir()) or entry.issym() or entry.islnk(): fail('Unsafe archive member; links/devices are forbidden')
            if not (name=='etc' or name.startswith('etc/') or name=='var' or name.startswith('var/')): fail('Unexpected state archive root')
        source.extractall(destination,filter='data')

def main(args):
    mode=args[0]
    if mode=='settings': result=settings(args[1],args[2] if len(args)>2 else None)
    elif mode=='capture': result=capture(Path(args[1]),args[2],args[3],json.loads(Path(args[4]).read_text()),json.loads(Path(args[5]).read_text()))
    elif mode=='verify': result=verify(Path(args[1]),args[2],args[3],json.loads(Path(args[4]).read_text()))
    elif mode=='extract': extract(args[1],args[2]);result={'extraction':'PASS'}
    else: fail('Unknown original backup mode')
    print(json.dumps(result,sort_keys=True))

if __name__=='__main__':
    try: main(sys.argv[1:])
    except (OSError,ValueError,KeyError,IndexError,tarfile.TarError) as error:
        print('CORE_ORIGINALS_BACKUP_REJECTED: '+str(error),file=sys.stderr);sys.exit(1)
