"""Portable local contracts and negative tests; not a claim that production was restored."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

MODULE=Path(__file__).resolve().parents[1]/'core-originals-manifest.py'
SPEC=importlib.util.spec_from_file_location('core_originals',MODULE)
BACKUP=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)

class CoreOriginalsTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name)
  self.root=self.base/'originals';self.root.mkdir();self.manifest=self.base/'manifest.json';self.stamp='20261024T231000+0800'
  self.config={'root':'/var/lib/hotel-ai-os/attachments','financeRelative':'finance-originals','businessRelative':'finance-business-originals','academyRelative':'academy'}
  self.refs=[]
  for domain,prefix,table in [('WORK','','attachment'),('FINANCE','finance-originals/','fs_asset_gain_original'),('FINANCE_BUSINESS','finance-business-originals/','fb_attachment'),('ACADEMY','academy/','academy_course')]:
   key='tenant/domain/source.original';path=self.root/(prefix+key);path.parent.mkdir(parents=True,exist_ok=True);data=(domain+' actual original').encode();path.write_bytes(data)
   self.refs.append({'domain':domain,'source_table':table,'source_id':domain,'object_key':key,'size_bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
  BACKUP.capture(self.root,self.manifest,self.stamp,self.config,self.refs)
 def test_roundtrip_checks_real_bytes_and_all_domain_database_indexes(self):
  result=BACKUP.verify(self.root,self.manifest,self.stamp,self.refs)
  self.assertEqual(result['restore'],'PASS');self.assertEqual(result['originalFiles'],4);self.assertEqual(result['databaseReferences'],4)
 def test_changed_same_size_original_is_not_a_pass(self):
  original=self.root/'finance-originals/tenant/domain/source.original';original.write_bytes(b'x'*original.stat().st_size)
  with self.assertRaisesRegex(ValueError,'frozen manifest'):BACKUP.verify(self.root,self.manifest,self.stamp,self.refs)
 def test_missing_gain_original_rejects_capture_and_restore(self):
  (self.root/'finance-originals/tenant/domain/source.original').unlink()
  with self.assertRaises(ValueError):BACKUP.verify(self.root,self.manifest,self.stamp,self.refs)
  with self.assertRaisesRegex(ValueError,'absent'):BACKUP.capture(self.root,self.manifest,self.stamp,self.config,self.refs)
 def test_restored_db_index_drift_or_missing_hash_cannot_be_claimed_verified(self):
  changed=copy.deepcopy(self.refs);changed[0]['source_id']='another-source'
  with self.assertRaisesRegex(ValueError,'index differs'):BACKUP.verify(self.root,self.manifest,self.stamp,changed)
  changed=copy.deepcopy(self.refs);changed[0]['sha256']=None
  with self.assertRaisesRegex(ValueError,'hash is missing'):BACKUP.verify(self.root,self.manifest,self.stamp,changed)
 def test_wrong_database_and_state_pair_rejected(self):
  with self.assertRaisesRegex(ValueError,'Wrong database'):BACKUP.verify(self.root,self.manifest,'20261025T231000+0800',self.refs)
 def test_real_safe_archive_extracts_only_into_isolated_root_and_verifies(self):
  archive=self.base/'safe.tar.gz'
  with tarfile.open(archive,'w:gz') as stream:
   stream.add(self.root,arcname='var/lib/hotel-ai-os/attachments');stream.add(self.manifest,arcname='var/lib/hotel-ai-os/backup-core-originals-manifest.json')
  target=self.base/'isolated';target.mkdir();BACKUP.extract(archive,target)
  result=BACKUP.verify(target/'var/lib/hotel-ai-os/attachments',target/'var/lib/hotel-ai-os/backup-core-originals-manifest.json',self.stamp,self.refs)
  self.assertEqual(result['originalFiles'],4)
 def test_database_object_path_cannot_escape_isolated_tree(self):
  changed=copy.deepcopy(self.refs);changed[0]['object_key']='../other'
  with self.assertRaisesRegex(ValueError,'Unsafe'):BACKUP.capture(self.root,self.manifest,self.stamp,self.config,changed)
 def test_original_symlink_is_not_followed(self):
  link=self.root/'bad-link'
  try:link.symlink_to(self.root/'tenant/domain/source.original')
  except OSError:self.skipTest('Host does not grant local symlink creation')
  with self.assertRaisesRegex(ValueError,'regular'):BACKUP.files(self.root)
 def test_configured_finance_and_business_roots_must_be_covered(self):
  old=BACKUP.LIVE;BACKUP.LIVE=self.root;self.addCleanup(setattr,BACKUP,'LIVE',old)
  env=self.base/'core.env';env.write_text('ATTACHMENT_STORAGE_ROOT='+str(self.root)+'\nFINANCE_BUSINESS_ATTACHMENTS_ROOT='+str(self.base/'outside')+'\n')
  with self.assertRaisesRegex(ValueError,'outside'):BACKUP.settings(env)
  env.write_text('ATTACHMENT_STORAGE_ROOT='+str(self.root)+'\nFINANCE_ATTACHMENTS_ROOT=var/finance-evidence\n')
  with self.assertRaisesRegex(ValueError,'outside'):BACKUP.settings(env)
  env.write_text('ATTACHMENT_STORAGE_ROOT='+str(self.root)+'\n');self.assertEqual(BACKUP.settings(env)['businessRelative'],'finance-business-originals')
 def test_effective_running_environment_cannot_hide_external_finance_root(self):
  old=BACKUP.LIVE;BACKUP.LIVE=self.root;self.addCleanup(setattr,BACKUP,'LIVE',old);env=self.base/'core.env';env.write_text('ATTACHMENT_STORAGE_ROOT='+str(self.root)+'\n')
  with mock.patch.object(BACKUP,'runtime_settings',return_value={'FINANCE_BUSINESS_ATTACHMENTS_ROOT':str(self.base/'outside')}):
   with self.assertRaisesRegex(ValueError,'outside'):BACKUP.settings(env,'123')
 def test_tar_traversal_links_devices_and_nonempty_restore_are_rejected(self):
  for name,kind in [('../escape',tarfile.REGTYPE),('var/link',tarfile.SYMTYPE),('var/device',tarfile.CHRTYPE)]:
   archive=self.base/'unsafe.tar.gz'
   with tarfile.open(archive,'w:gz') as stream:
    item=tarfile.TarInfo(name);item.type=kind;item.linkname='/etc/passwd';stream.addfile(item,io.BytesIO())
   target=self.base/'restore';target.mkdir(exist_ok=True)
   with self.assertRaises(ValueError):BACKUP.extract(archive,target)
  (target/'already-there').write_text('retain')
  with self.assertRaisesRegex(ValueError,'empty isolated'):BACKUP.extract(archive,target)
 def test_daily_job_stops_core_before_dump_and_captures_before_resuming(self):
  source=(MODULE.parent/'backup-postgres.sh').read_text();self.assertIn('  hotel-ai-os-core-api.service',source)
  stop=source.index('then systemctl stop "${resume_services[@]}"')
  dump=source.index('sudo -u postgres pg_dump');capture=source.index('"${original_tool}" capture');resume=source.index('\nresume_writers\n')
  self.assertLess(stop,dump);self.assertLess(dump,capture);self.assertLess(capture,resume)
  self.assertNotIn('find "${source_core}"',source);self.assertIn('trap cleanup EXIT INT TERM',source)
 def test_both_core_staging_copies_preserve_acls_xattrs_and_numeric_owners(self):
  source=(MODULE.parent/'backup-postgres.sh').read_text()
  copies=[line for line in source.splitlines() if line.startswith('rsync ') and '"${source_core}/"' in line]
  self.assertEqual(len(copies),2,'Both the online bulk copy and quiesced final copy must preserve source metadata')
  for line in copies:
   self.assertTrue(line.startswith('rsync -aHAX --numeric-ids '),line)
   self.assertIn('"${stage_core}/"',line)
   self.assertNotIn('--no-acls',line);self.assertNotIn('--no-xattrs',line)
 def test_restore_never_targets_live_database_or_original_root(self):
  source=(MODULE.parent/'rehearse-core-originals-restore.sh').read_text();self.assertIn('hotel_ai_os_originals_restore_',source)
  self.assertIn('--dbname "$restore_db"',source);self.assertNotIn('--dbname hotel_ai_os ',source)
  self.assertIn('"$tool" extract "$work/state.tar.gz" "$work/state"',source)
  self.assertNotIn('cp ',source);self.assertIn('realpath -e "$work"',source)

if __name__=='__main__':unittest.main(verbosity=2)
