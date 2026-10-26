import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

PATH = Path(__file__).resolve().parents[1] / 'full-ci-release.py'
spec = importlib.util.spec_from_file_location('full_ci', PATH)
F = importlib.util.module_from_spec(spec); spec.loader.exec_module(F)

def zipped(values):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w') as archive:
        for name, value in values.items(): archive.writestr(name, value)
    return result.getvalue()

def encoded(value): return json.dumps(value, separators=(',', ':')).encode()

class FullCiReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.commit = 'a'*40; self.classifier = 'b'*64
        self.web = {'index.html': b'<html>synthetic</html>', 'assets/app.js': b'synthetic bearer'}
        self.sql = {'BOOT-INF/classes/db/migration/V1__synthetic.sql': b'select 1;', 'BOOT-INF/classes/db/migration/V2__synthetic.sql': b'select 2;'}
        self.jar = {**self.sql, 'BOOT-INF/lib/synthetic.jar': zipped({'library': b'synthetic'}), 'BOOT-INF/classes/NotAllowed.class': b'not executable'}
        self.files = {'web.zip': zipped(self.web), 'core-api.jar': zipped(self.jar), 'run-migration-once.sh': b'#!/bin/sh\nexit 0\n', 'finance-ocr.zip': b'synthetic: separately verified by fixed OCR PREPARE'}
        self.plan = {'schemaVersion': 1, 'status': 'PASS', 'lane': 'FULL', 'baseRef': 'c'*40, 'headRef': self.commit, 'policyVersion': 3, 'classifierSha256': self.classifier, **{key: True for key in ('requiresWeb','requiresCore','requiresDatabaseBackup','requiresMigrationGate','requiresFullRegression')}}
        self.manifest = {'schemaVersion':1,'status':'PASS','lane':'FULL','sourceCommit':self.commit,'policy':{'version':3,'classifierSha256':self.classifier},'safeguards':{'deployRequiresEnvironmentApproval':True,'fullLaneAutoDeployAllowed':False,'sourceCommitBound':True,'hashesRequired':True},'artifacts':[]}
        self.refresh()

    def refresh(self):
        self.manifest['planSha256'] = F.sha(encoded(self.plan))
        self.manifest['artifacts'] = [{'name':name,'file':file,'sha256':F.sha(self.files[file]),'sizeBytes':len(self.files[file])} for name,file in F.ARTIFACTS.items()]
        tree = '\n'.join(f'{name}:{len(raw)}:{F.sha(raw)}' for name,raw in sorted(self.web.items()))
        self.manifest['core']={'jarSha256':F.sha(self.files['core-api.jar'])}
        self.manifest['web']={'bundleSha256':F.sha(self.files['web.zip']),'indexSha256':F.sha(self.web['index.html']),'treeSha256':F.sha(tree.encode()),'treeFingerprintAlgorithm':'sha256(sorted-path-colon-size-colon-content-sha256-v1)'}
        self.manifest['migration']={'version':'2','migrationCount':2,'fingerprint':F.sha('\n'.join(f'{name}:{F.sha(raw)}' for name,raw in sorted(self.sql.items())).encode()),'algorithm':'sha256(sorted-entry-name-colon-content-sha256-v1)'}
        self.manifest['financeOcr']={'sourceCommit':self.commit,'bundleSha256':F.sha(self.files['finance-ocr.zip']),'bundleManifestSha256':'d'*64,'requirementsSha256':'e'*64,'pythonMajorMinor':'3.12','platform':'linux-x86_64'}

    def archive(self, extra=None):
        values = {**self.files,'deployment-plan.json':encoded(self.plan),'release-manifest.json':encoded(self.manifest),**(extra or {})}
        path = self.root/'release.zip'; path.write_bytes(zipped(values)); return path

    def verify(self, extra=None):
        path = self.archive(extra); return F.verify(path,F.sha(path.read_bytes()),self.commit,self.classifier)

    def test_exact_full_closure_and_same_source_are_accepted(self):
        self.assertEqual(self.verify()['sourceCommit'],self.commit)

    def test_changed_outer_digest_rejected_before_extraction(self):
        path=self.archive()
        with self.assertRaisesRegex(ValueError,'digest'): F.verify(path,'0'*64,self.commit,self.classifier)

    def test_private_copy_accepts_only_the_exact_initial_size(self):
        original = self.root/'incoming.zip'; original.write_bytes(b'a'*(1024**2+31))
        target = self.root/'private.zip'
        with original.open('rb') as source, target.open('xb') as output:
            F.copy_private_artifact(source,output,os.fstat(source.fileno()))
        self.assertEqual(target.read_bytes(),original.read_bytes())

    def test_appending_writer_cannot_extend_the_root_copy(self):
        original = self.root/'incoming.zip'; original.write_bytes(b'a'*(2*1024**2))
        target = self.root/'private.zip'; reads=[]
        with original.open('rb') as source, target.open('xb') as output:
            before=os.fstat(source.fileno())
            class AppendingSource:
                def fileno(self): return source.fileno()
                def read(self,size):
                    reads.append(size)
                    block=source.read(size)
                    with original.open('ab') as writer: writer.write(b'x'*512)
                    return block
            with self.assertRaisesRegex(ValueError,'grew'):
                F.copy_private_artifact(AppendingSource(),output,before)
        self.assertEqual(reads,[1024**2,1024**2,1])
        self.assertEqual(target.stat().st_size,before.st_size)
        self.assertGreater(original.stat().st_size,before.st_size)

    def test_private_copy_rejects_short_reads_and_metadata_drift(self):
        original = self.root/'incoming.zip'; original.write_bytes(b'abcd')
        with original.open('rb') as source, (self.root/'short.zip').open('xb') as output:
            before=os.fstat(source.fileno())
            with original.open('r+b') as writer: writer.truncate(2)
            with self.assertRaisesRegex(ValueError,'shortened'):
                F.copy_private_artifact(source,output,before)
        original.write_bytes(b'abcd')
        with original.open('rb') as source, (self.root/'drift.zip').open('xb') as output:
            before=os.fstat(source.fileno())
            class ChangedSource:
                def fileno(self): return source.fileno()
                def read(self,size):
                    result=source.read(size)
                    os.utime(original,ns=(before.st_atime_ns,before.st_mtime_ns+2_000_000_000))
                    return result
            with self.assertRaisesRegex(ValueError,'changed'):
                F.copy_private_artifact(ChangedSource(),output,before)

    def test_extra_and_missing_members_fail_closed(self):
        for name in ('unexpected.sh','../escape','nested/file'):
            with self.subTest(name=name),self.assertRaises(ValueError): self.verify({name:b'bad'})
        del self.files['finance-ocr.zip']
        with self.assertRaises(ValueError): self.verify()

    def test_duplicate_and_symlink_outer_members_rejected(self):
        path=self.archive()
        with zipfile.ZipFile(path,'a') as archive:
            entry=zipfile.ZipInfo('link'); entry.external_attr=(stat.S_IFLNK|0o777)<<16; archive.writestr(entry,'/etc/passwd')
        with self.assertRaisesRegex(ValueError,'Unsafe'): F.verify(path,F.sha(path.read_bytes()),self.commit,self.classifier)
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as archive:
            archive.writestr('same','a'); archive.writestr('same','b')
        with zipfile.ZipFile(io.BytesIO(stream.getvalue())) as archive:
            with self.assertRaises(ValueError): F.entries(archive)

    def test_full_gates_and_environment_approval_cannot_be_disabled(self):
        for field in ('requiresWeb','requiresCore','requiresDatabaseBackup','requiresMigrationGate','requiresFullRegression'):
            old=self.plan[field]; self.plan[field]=False; self.refresh()
            with self.subTest(field=field),self.assertRaises(ValueError): self.verify()
            self.plan[field]=old
        self.refresh(); self.manifest['safeguards']['fullLaneAutoDeployAllowed']=True
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_source_policy_and_component_hash_fail(self):
        for key,value in [('sourceCommit','f'*40),('policy',{'version':2,'classifierSha256':self.classifier})]:
            old=copy.deepcopy(self.manifest[key]); self.manifest[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError): self.verify()
            self.manifest[key]=old
        self.manifest['artifacts'][0]['sha256']='0'*64
        with self.assertRaises(ValueError): self.verify()

    def test_nested_web_escape_and_changed_tree_fail(self):
        self.files['web.zip']=zipped({**self.web,'../escape':b'bad'}); self.refresh()
        with self.assertRaises(ValueError): self.verify()
        self.files['web.zip']=zipped(self.web); self.refresh(); self.manifest['web']['treeSha256']='0'*64
        with self.assertRaises(ValueError): self.verify()

    def test_migration_max_version_and_fingerprint_are_not_trusted_from_manifest(self):
        self.manifest['migration']['version']='1'
        with self.assertRaises(ValueError): self.verify()
        self.refresh(); self.manifest['migration']['fingerprint']='0'*64
        with self.assertRaises(ValueError): self.verify()
        self.jar['BOOT-INF/classes/db/migration/V2__duplicate.sql']=b'select 3;'
        self.files['core-api.jar']=zipped(self.jar); self.refresh()
        with self.assertRaisesRegex(ValueError,'Duplicate migration'): self.verify()

    def test_runtime_contains_libraries_sql_only_never_application_classes(self):
        jar=self.root/'candidate.jar'; jar.write_bytes(self.files['core-api.jar'])
        target=self.root/'runtime'
        with patch.object(F,'account',return_value=SimpleNamespace(pw_gid=0)),patch.object(F.os,'chown',create=True): F.runtime(jar,target)
        self.assertTrue((target/'lib/synthetic.jar').is_file())
        self.assertTrue((target/'classes/db/migration/V1__synthetic.sql').is_file())
        self.assertFalse((target/'classes/NotAllowed.class').exists())

if __name__=='__main__': unittest.main()
