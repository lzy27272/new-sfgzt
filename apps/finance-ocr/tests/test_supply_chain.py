import hashlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from finance_ocr.models import MODELS, verified_models
import verify_dependencies


class ModelSupplyTests(unittest.TestCase):
    def test_only_pinned_version_is_accepted(self):
        with patch('finance_ocr.models.distribution', return_value=SimpleNamespace(version='other')):
            with self.assertRaisesRegex(RuntimeError, 'version'):
                verified_models()

    def test_missing_bundled_model_fails_without_download(self):
        fake = SimpleNamespace(version='3.10.0', files=[])
        with patch('finance_ocr.models.distribution', return_value=fake):
            with self.assertRaisesRegex(RuntimeError, 'missing'):
                verified_models()

    def test_all_model_hashes_have_distinct_verified_identity(self):
        self.assertEqual(len(MODELS), 3)
        self.assertEqual(len({sha for _, sha in MODELS.values()}), 3)
        for name, sha in MODELS.values():
            self.assertTrue(name.endswith('.onnx')); self.assertRegex(sha, r'^[0-9a-f]{64}$')


class WheelSupplyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.wheel = Path(self.temp.name) / 'fixture-1.0-py3-none-any.whl'
        with zipfile.ZipFile(self.wheel, 'w') as archive:
            archive.writestr('fixture-1.0.dist-info/METADATA', 'Name: fixture\nVersion: 1.0\n')
        self.sha = hashlib.sha256(self.wheel.read_bytes()).hexdigest()
        self.official = {'urls': [{'filename': self.wheel.name, 'digests': {'sha256': self.sha}, 'packagetype': 'bdist_wheel'}], 'vulnerabilities': []}

    def verify(self):
        raw = io.BytesIO(json.dumps(self.official).encode())
        with patch('verify_dependencies.urllib.request.urlopen', return_value=raw):
            return verify_dependencies.verify(self.wheel)

    def test_exact_official_hash_required(self):
        self.assertEqual(self.verify()[2], self.sha)
        self.official['urls'][0]['digests']['sha256'] = 'a' * 64
        with self.assertRaises(ValueError):
            self.verify()

    def test_yanked_and_reported_vulnerable_release_rejected(self):
        self.official['urls'][0]['yanked'] = True
        with self.assertRaises(ValueError):
            self.verify()
        self.official['urls'][0]['yanked'] = False
        self.official['vulnerabilities'] = [{'id': 'SYNTHETIC-TEST'}]
        with self.assertRaises(ValueError):
            self.verify()


if __name__ == '__main__':
    unittest.main()
