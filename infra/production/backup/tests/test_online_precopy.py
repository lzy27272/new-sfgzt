"""Execute the production copy commands with rsync stubbed; never run a real backup."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE=(Path(__file__).resolve().parents[1]/'backup-postgres.sh').read_text()
START=SOURCE.index('online_precopy() {\n')
HELPER=SOURCE[START:SOURCE.index('\n}\n',START)+3]
ONLINE=SOURCE[SOURCE.index('# First pass copies the bulk'):SOURCE.index('\nwriter_timers=(')]
FINAL=SOURCE[SOURCE.index('\nsudo -u postgres pg_dump'):SOURCE.index('\ninstall -d -m 0750',SOURCE.index('\nsudo -u postgres pg_dump'))]
FINAL_COPIES='\n'.join(line.strip() for line in FINAL.splitlines() if line.strip().startswith('rsync '))
OPTIONS=SOURCE[SOURCE.index('state_rsync_options=('):SOURCE.index('\n# Identical validated reuse condition')]


class OnlinePrecopyTest(unittest.TestCase):
 def run_copy(self,online_exit=0,final_exit=0,capacity_exit=None):
  with tempfile.TemporaryDirectory() as root:
   # Stub all copying, use only synthetic paths, and retain the actual option arrays/call sites.
   environment=os.environ.copy();environment['ONLINE_EXIT']=str(online_exit);environment['FINAL_EXIT']=str(final_exit)
   script='''set -Eeuo pipefail
source_state=/synthetic/state
source_core=/synthetic/core
stage_state=/synthetic/stage
stage_core=/synthetic/stage/core
stage_raw=/synthetic/stage/raw
source_raw=.
measurement_root=.
phase=ONLINE
rsync() {
 printf 'RSYNC_PHASE=%s' "$phase"
 printf ' ARG=<%s>' "$@"
 printf '\\n'
 if [[ $phase == ONLINE ]]; then return "$ONLINE_EXIT"; fi
 return "$FINAL_EXIT"
}
'''+OPTIONS+'\n'+HELPER+'\n'
   if capacity_exit is None:
    script+=ONLINE+'\nprintf "ONLINE_COMPLETED\\n"\nphase=FINAL\n'+FINAL_COPIES+'\nprintf "FINAL_COMPLETED\\n"\n'
   else:
    start=SOURCE.index('  LC_ALL=C rsync ')
    # The first real capacity dry-run is intentionally outside the permissive helper.
    command=SOURCE[start:SOURCE.index('\n',SOURCE.index('\n',start)+1)]
    environment['ONLINE_EXIT']=str(capacity_exit)
    script+=command+'\nprintf "CAPACITY_COMPLETED\\n"\n'
   binary=shutil.which('bash')
   self.assertIsNotNone(binary,'Bash is required to validate actual backup shell behavior')
   return subprocess.run([binary,'--noprofile','--norc','-s'],input=script,text=True,
                         capture_output=True,cwd=root,env=environment,timeout=15)

 def test_successful_online_and_final_copy_keep_all_six_copy_operations(self):
  result=self.run_copy()
  self.assertEqual(result.returncode,0,result.stderr)
  self.assertEqual(result.stdout.count('RSYNC_PHASE=ONLINE'),3)
  self.assertEqual(result.stdout.count('RSYNC_PHASE=FINAL'),3)
  self.assertIn('FINAL_COMPLETED',result.stdout)
  self.assertNotIn('BACKUP_ONLINE_PRECOPY_VANISHED',result.stdout)
  self.assertEqual(result.stdout.count('ARG=<-aHAX>'),4)
  self.assertEqual(result.stdout.count('ARG=<--numeric-ids>'),4)
  self.assertNotIn('/Sessions/',OPTIONS)
  self.assertNotIn('/Cookies',OPTIONS)

 def test_online_vanished_files_are_logged_and_all_final_copies_are_required(self):
  result=self.run_copy(online_exit=24)
  self.assertEqual(result.returncode,0,result.stderr)
  self.assertEqual(result.stdout.count('BACKUP_ONLINE_PRECOPY_VANISHED'),3)
  for domain in ('OTA_STATE','CORE_ORIGINALS','OTA_RAW'):
   self.assertIn('domain='+domain+' rsyncExit=24 finalSync=REQUIRED',result.stdout)
  self.assertEqual(result.stdout.count('RSYNC_PHASE=FINAL'),3)
  self.assertIn('FINAL_COMPLETED',result.stdout)

 def test_other_online_io_permission_or_partial_errors_fail_before_final_copy(self):
  for code in (23,11,12,13):
   with self.subTest(code=code):
    result=self.run_copy(online_exit=code)
    self.assertEqual(result.returncode,code,result.stdout+result.stderr)
    self.assertNotIn('ONLINE_COMPLETED',result.stdout)
    self.assertNotIn('RSYNC_PHASE=FINAL',result.stdout)

 def test_final_vanished_or_partial_copy_never_becomes_a_successful_snapshot(self):
  for code in (24,23,11):
   with self.subTest(code=code):
    result=self.run_copy(online_exit=24,final_exit=code)
    self.assertEqual(result.returncode,code,result.stdout+result.stderr)
    self.assertIn('ONLINE_COMPLETED',result.stdout)
    self.assertNotIn('FINAL_COMPLETED',result.stdout)
    self.assertEqual(result.stdout.count('RSYNC_PHASE=FINAL'),1)

 def test_capacity_dry_run_is_still_fail_closed_for_vanished_or_partial_sources(self):
  capacity=SOURCE[SOURCE.index('check_backup_capacity() ('):SOURCE.index('\n# Fail before allocating')]
  self.assertNotIn('online_precopy',capacity)
  for code in (24,23):
   with self.subTest(code=code):
    result=self.run_copy(capacity_exit=code)
    self.assertEqual(result.returncode,code,result.stdout+result.stderr)
    self.assertNotIn('CAPACITY_COMPLETED',result.stdout)
    self.assertNotIn('BACKUP_ONLINE_PRECOPY_VANISHED',result.stdout)


if __name__=='__main__':unittest.main(verbosity=2)
