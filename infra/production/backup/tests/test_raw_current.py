import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('snapshots',BASE/'core-state-snapshots.py')
S=importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


class RawCurrentTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'raw'
        self.source=Path(self.temp.name)/'source'
        self.root.mkdir()
        self.source.mkdir()

    def archive(self,day,content):
        digest=hashlib.sha256(content).hexdigest()
        path=self.root/('analytics-raw-'+day+'-'+digest+'.tar.gz.enc')
        path.write_bytes(content)
        S.checksum(path)
        return path

    def retained_day(self,day):
        folder=self.source/day
        folder.mkdir()
        (folder/('a'*64+'.json.enc')).write_bytes(b'held')
        found=subprocess.run(['find',str(folder),'-xdev','-type','f','-name','*.enc',
            '-printf','%P\\0%s\\0%T@\\0'],check=True,capture_output=True).stdout
        ordered=subprocess.run(['sort','-z'],input=found,check=True,capture_output=True).stdout
        fingerprint=hashlib.sha256(ordered).hexdigest()
        current=self.archive(day,b'current held evidence')
        (self.root/('.analytics-raw-'+day+'.source.sha256')).write_text(fingerprint+' '+current.name+'\n')
        return current

    def test_old_held_day_survives_but_obsolete_revisions_do_not(self):
        current=self.retained_day('2025-01-01')
        obsolete=self.archive('2025-01-01',b'old mixed ordinary and held evidence')
        expired=self.archive('2026-10-01',b'expired ordinary evidence')
        result=S.prune_raw_current(self.root,self.source,'2026-11-07')
        self.assertEqual(result,{'retainedArtifacts':1,'removed':2})
        self.assertTrue(current.exists())
        self.assertFalse(obsolete.exists())
        self.assertFalse(expired.exists())

    def test_missing_current_package_preserves_everything(self):
        current=self.retained_day('2025-01-01')
        obsolete=self.archive('2025-01-01',b'old')
        current.unlink()
        with self.assertRaises((ValueError,FileNotFoundError)):
            S.prune_raw_current(self.root,self.source,'2026-11-07')
        self.assertTrue(obsolete.exists())

    def test_changed_source_preserves_old_packages(self):
        current=self.retained_day('2025-01-01')
        obsolete=self.archive('2025-01-01',b'old')
        (self.source/'2025-01-01'/('a'*64+'.json.enc')).write_bytes(b'new evidence')
        with self.assertRaises(ValueError):
            S.prune_raw_current(self.root,self.source,'2026-11-07')
        self.assertTrue(current.exists())
        self.assertTrue(obsolete.exists())

    def test_no_remaining_raw_does_not_keep_expired_archives(self):
        expired=self.archive('2026-10-01',b'expired')
        result=S.prune_raw_current(self.root,self.source,'2026-11-07')
        self.assertEqual(result,{'retainedArtifacts':0,'removed':1})
        self.assertFalse(expired.exists())


if __name__=='__main__':
    unittest.main()
