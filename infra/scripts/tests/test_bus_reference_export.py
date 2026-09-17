import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('reference_export',Path(__file__).parents[1]/'export-bus-reference.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ExportTest(unittest.TestCase):
    def test_preserves_source_mean_and_identity(self):
        row=m.convert(('r','a','b','1','2',2,8,180,3,'2026-08-01','2026-08-17'),'master','a'*64)
        self.assertEqual(row['meanSeconds'],60)
        self.assertEqual(row['sampleKind'],'HOURLY_SOURCE_MEAN')
        self.assertEqual(row['fromOrder'],1)
        self.assertNotIn('weatherBucket',row)
        self.assertNotIn('p50',row)
    def test_rejects_zero_and_missing_samples(self):
        for total,count in [(0,3),(60,0)]:
            with self.assertRaises(ValueError):m.convert(('r','a','b','1','2',2,8,total,count,'2026-08-01','2026-08-17'),'master','a'*64)

if __name__=='__main__':unittest.main()
