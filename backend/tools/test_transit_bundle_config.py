"""추적 마스터를 교체하고 Compose의 기본 지문을 빠뜨려 수집기가 기동하지 못하는 회귀를 방어한다.

운영 .env나 비밀값은 읽지 않는다. 저장소의 압축 바이트, manifest, 무설정 실행 기본값만 비교한다.
"""
import hashlib
import json
from pathlib import Path
import re
import unittest


class TransitBundleConfigTest(unittest.TestCase):
    def test_compose_defaults_match_bundled_master_and_crosswalk(self):
        root = Path(__file__).resolve().parents[2]
        compose = (root / 'infra/docker-compose.yml').read_text(encoding='utf-8')
        bus = root / 'backend/transit-master/bus'
        for variable, stem in [
            ('BUS_CROSSWALK_SHA256', 'metropolitan-bus-v6.crosswalk.json'),
            ('DDUBI_METROPOLITAN_BUS_SHA256', 'metropolitan-bus-v6.json'),
        ]:
            with self.subTest(variable=variable):
                configured = re.search(r'\$\{' + variable + r':-([a-f0-9]{64})\}', compose)
                self.assertIsNotNone(configured, '공유 마스터의 기본 SHA가 필요합니다')
                actual = hashlib.sha256((bus / (stem + '.gz')).read_bytes()).hexdigest()
                manifest = json.loads((bus / (stem + '.manifest.json')).read_text(encoding='utf-8'))
                self.assertEqual(actual, manifest['sha256'])
                self.assertEqual(configured.group(1), actual)


if __name__ == '__main__':
    unittest.main()
