"""Native OpenSSL + real regular-file FD/early-consumer tests; no real database or sudo."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
SCRIPTS = (BASE / 'rehearse-core-originals-restore.sh', BASE.parent / 'bootstrap/63-install-full-ci-release.sh')


def function(path, name):
    source = path.read_text(encoding='utf-8')
    start = source.index(name + '(){')
    return source[start:source.index('\n}', start) + 2]


@unittest.skipUnless(shutil.which('openssl') and shutil.which('bash'), 'Existing native OpenSSL and Bash required')
class FileDatabaseRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='file-database-restore-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.key = self.root / 'synthetic.key'
        self.key.write_bytes(b'synthetic-native-test-key-never-production-0001')
        self.payload = os.urandom(2 * 1024**2)
        self.original = self.root / 'original.dump'; self.original.write_bytes(self.payload)
        self.encrypted = self.root / 'database.dump.enc'
        subprocess.run(['openssl','enc','-aes-256-cbc','-pbkdf2','-iter','200000','-salt','-pass','file:'+str(self.key),
                        '-in',str(self.original),'-out',str(self.encrypted)],check=True,capture_output=True)
        self.consumer = self.root / 'consumer.py'
        self.consumer.write_text("import os,pathlib,sys\npathlib.Path(os.environ['PG_STARTED']).write_text('started')\n"
            "assert sys.stdin.buffer.seekable(), 'restore stdin must be a regular seekable file'\n"
            "sys.stdin.buffer.read(16)\n"
            "if os.environ.get('PG_MODE')=='error':\n sys.stderr.write('synthetic pg_restore failure\\n');sys.exit(9)\n",encoding='utf-8')

    def run_restore(self, script, mode='close', archive=None, cleanup=False):
        plain = self.root / (script.stem + '.dump'); plain.touch(); plain.chmod(0o600)
        marker = self.root / (script.stem + '.started'); marker.unlink(missing_ok=True)
        code = 'set -Eeuo pipefail\numask 077\n' + function(script,'restore_database_file') + '\n'
        code += 'sudo(){ test "$1" = -u && test "$2" = postgres && test "$3" = pg_restore || return 97; "$TEST_PYTHON" "$TEST_CONSUMER"; }\n'
        if cleanup:
            plain.unlink(); plain=self.root/'.full-ci-clone.Abc12345.dump'; plain.touch(); plain.chmod(0o600)
            guard = function(SCRIPTS[1],'cleanup_clone_dump').replace('^/var/backups/hotel-ai-os/postgres/', '^'+re.escape(self.root.as_posix()+'/'))
            code += guard + '\nclone_dump=$2\ntrap cleanup_clone_dump EXIT\n'
        code += 'restore_database_file "$1" fixture_db "$2" "$3"\n'
        env={**os.environ,'TEST_PYTHON':Path(sys.executable).as_posix(),'TEST_CONSUMER':self.consumer.as_posix(),
             'PG_STARTED':marker.as_posix(),'PG_MODE':mode}
        result=subprocess.run(['bash','-c',code,'fixture',(archive or self.encrypted).as_posix(),plain.as_posix(),self.key.as_posix()],
                               env=env,capture_output=True,text=True,timeout=30)
        return result,plain,marker

    def test_actual_pipe_early_close_fails_but_both_file_paths_finish_decryption_before_consumer(self):
        producer=subprocess.Popen(['openssl','enc','-d','-aes-256-cbc','-pbkdf2','-iter','200000','-pass','file:'+str(self.key),
                                   '-in',str(self.encrypted)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        consumer=subprocess.Popen([sys.executable,'-c','import sys;sys.stdin.buffer.read(16)'],stdin=producer.stdout,
                                   stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        producer.stdout.close()
        self.assertEqual(consumer.wait(timeout=30),0)
        producer.wait(timeout=30); error=producer.stderr.read(); producer.stderr.close(); consumer.stderr.close()
        self.assertNotEqual(producer.returncode,0)
        self.assertTrue(error)
        for script in SCRIPTS:
            result,plain,marker=self.run_restore(script)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertTrue(marker.is_file())
            self.assertEqual(plain.read_bytes(),self.payload)
            if os.name!='nt': self.assertEqual(plain.stat().st_mode & 0o777,0o600)

    def test_full_decrypt_padding_failure_never_starts_restore(self):
        truncated=self.root/'truncated.enc';truncated.write_bytes(self.encrypted.read_bytes()[:-1])
        for script in SCRIPTS:
            result,plain,marker=self.run_restore(script,archive=truncated)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(marker.exists())
            self.assertIn('decrypt',result.stderr.lower())

    def test_restore_error_remains_failure_and_own_temp_is_cleaned_without_touching_ciphertext(self):
        before=self.encrypted.read_bytes()
        for mode in ('close','error'):
            result,plain,marker=self.run_restore(SCRIPTS[1],mode=mode,cleanup=True)
            self.assertEqual(result.returncode,0 if mode=='close' else 9,result.stderr)
            self.assertTrue(marker.exists())
            self.assertFalse(plain.exists())
            self.assertEqual(self.encrypted.read_bytes(),before)
        for script in SCRIPTS:
            result,_,marker=self.run_restore(script,mode='error')
            self.assertEqual(result.returncode,9,result.stderr)
            self.assertTrue(marker.exists())

    def test_restore_file_functions_match_and_clone_cleanup_refuses_other_files(self):
        self.assertEqual(function(SCRIPTS[0],'restore_database_file'),function(SCRIPTS[1],'restore_database_file'))
        guard=function(SCRIPTS[1],'cleanup_clone_dump').replace('^/var/backups/hotel-ai-os/postgres/', '^'+re.escape(self.root.as_posix()+'/'))
        code='set -Eeuo pipefail\n'+guard+'\nclone_dump=$1\ncleanup_clone_dump\n'
        result=subprocess.run(['bash','-c',code,'fixture',self.encrypted.as_posix()],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertTrue(self.encrypted.is_file())


if __name__=='__main__': unittest.main(verbosity=2)
