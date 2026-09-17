import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('bundle', Path(__file__).parents[1] / 'verify-transit-bundle.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class BundleTests(unittest.TestCase):
    def test_incomplete(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertFalse(bundle.verify(Path(root))['integrityVerified'])

    def test_integrity_is_not_approval_and_tamper_fails(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            artifact = root / 'data.jsonl'
            artifact.write_text('{}', encoding='utf-8')
            manifest = dict(sourceSha256='a'*64, codeSha256='b'*64,
                            productionReady=True, outputs={artifact.name:bundle.digest(artifact)})
            (root/'complete.json').write_text(json.dumps(manifest),encoding='utf-8')
            result = bundle.verify(root)
            self.assertTrue(result['integrityVerified'])
            self.assertFalse(result['productionApproved'])
            artifact.write_text('changed',encoding='utf-8')
            self.assertFalse(bundle.verify(root)['integrityVerified'])

    def test_path_escape(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root/'complete.json').write_text(json.dumps(dict(sourceSha256='a'*64,
                codeSha256='b'*64, outputs={'../outside':'a'*64})),encoding='utf-8')
            self.assertIn('OUTPUT_PATH_ESCAPE',bundle.verify(root)['failures'])


if __name__=='__main__': unittest.main()
