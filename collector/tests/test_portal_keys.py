"""포털 공통키 우선순위와 기존 변수 fallback 검증."""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from config import Settings  # noqa: E402
from sources import subway_alert, topis_notice, traffic_incident  # noqa: E402
from sources.kafka_adapter import collect  # noqa: E402


class PortalKeyTest(unittest.TestCase):
    def settings(self, keys):
        return Settings(source="traffic_incident", kafka_bootstrap_servers="unused", api_keys=keys)

    def test_seoul_general_sources_share_one_key(self):
        settings = self.settings({"SEOUL_OPEN_DATA_API_KEY": "shared", "TRAFFIC_INCIDENT_API_KEY": "legacy"})
        for source_name in (
            "SUBWAY_ALERT_API_KEY", "CITY_REALTIME_API_KEY", "TOPIS_NOTICE_API_KEY", "TRAFFIC_INCIDENT_API_KEY"
        ):
            self.assertEqual(settings.api_key(source_name), "shared")

    def test_environment_loads_new_portal_variable(self):
        with patch.dict(os.environ, {
            "COLLECTOR_SOURCE": "traffic_incident", "KAFKA_BOOTSTRAP_SERVERS": "unused",
            "SEOUL_OPEN_DATA_API_KEY": "shared",
        }, clear=True):
            settings = Settings.from_env()
        self.assertEqual(settings.api_key("TRAFFIC_INCIDENT_API_KEY"), "shared")

    def test_data_go_kr_sources_share_one_key(self):
        settings = self.settings({"DATA_GO_KR_API_KEY": "portal"})
        for source_name in ("BUS_ARRIVAL_API_KEY", "BUS_LOCATION_API_KEY", "GBIS_API_KEY", "INCHEON_API_KEY"):
            self.assertEqual(settings.api_key(source_name), "portal")

    def test_special_subway_key_stays_separate_and_legacy_fallback_works(self):
        settings = self.settings({"SEOUL_OPEN_DATA_API_KEY": "general", "SUBWAY_API_KEY": "special"})
        self.assertEqual(settings.api_key("SUBWAY_API_KEY"), "special")
        legacy = self.settings({"TRAFFIC_INCIDENT_API_KEY": "old"})
        self.assertEqual(legacy.api_key("TRAFFIC_INCIDENT_API_KEY"), "old")

    def test_standalone_seoul_collectors_use_portal_key(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text("", encoding="utf-8")
            with patch.dict(os.environ, {
                "SEOUL_OPEN_DATA_API_KEY": "shared", "TRAFFIC_INCIDENT_API_KEY": "old-incident",
                "TOPIS_NOTICE_API_KEY": "old-notice", "SUBWAY_ALERT_API_KEY": "old-alert",
            }):
                self.assertEqual(traffic_incident.require_api_key(env_file), "shared")
                self.assertEqual(topis_notice.require_api_key(env_file), "shared")
                self.assertEqual(subway_alert.require_api_key(), "shared")

    def test_traffic_incident_collector_uses_shared_key(self):
        xml = """<AccInfo><list_total_count>1</list_total_count>
        <RESULT><CODE>INFO-000</CODE></RESULT>
        <row><ACC_ID>i1</ACC_ID><LINK_ID>100</LINK_ID></row></AccInfo>"""
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, text=xml)

        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(
                source="traffic_incident",
                kafka_bootstrap_servers="unused",
                api_keys={"SEOUL_OPEN_DATA_API_KEY": "shared"},
                extra={"API_BUDGET_PATH": str(Path(directory) / "budget.sqlite3")},
            )
            with httpx.Client(transport=httpx.MockTransport(respond)) as client:
                batch = collect("traffic_incident", settings, client)

        self.assertEqual(len(requests), 1)
        self.assertIn("/shared/xml/AccInfo/1/1000/", str(requests[0].url))
        self.assertTrue(batch.complete_snapshot)
        self.assertEqual(batch[0].payload["providerLinkId"], "100")


if __name__ == "__main__":
    unittest.main()
