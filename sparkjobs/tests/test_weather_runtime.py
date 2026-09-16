"""Configuration omissions and multi-query failure cleanup, without a Spark dependency."""
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common.weather_runtime import silver_configuration, supervise


class WeatherRuntimeTests(unittest.TestCase):
    def test_required_missing_or_blank_fails(self):
        for env in ({"WEATHER_REQUIRE_SILVER": "true"}, {"WEATHER_REQUIRE_SILVER": "true", "SILVER_OUTPUT_URI": "  "}):
            with self.assertRaisesRegex(RuntimeError, "SILVER_OUTPUT_URI is required"):
                silver_configuration(env)

    def test_optional_replay_remains_disabled(self):
        self.assertIsNone(silver_configuration({}))
        self.assertIsNone(silver_configuration({"WEATHER_REQUIRE_SILVER": "false"}))

    def test_target_and_invalid_uri(self):
        self.assertEqual(silver_configuration({"SILVER_OUTPUT_URI": "hdfs://hdfs-namenode:9000/ddubi/silver/"}), "hdfs://hdfs-namenode:9000/ddubi/silver/weather")
        for value in ("/ddubi/silver", "hdfs:///ddubi/silver", "https://example.com/a", "hdfs://host/x?bad=true"):
            with self.assertRaises(ValueError):
                silver_configuration({"SILVER_OUTPUT_URI": value})

    def test_invalid_required_flag(self):
        with self.assertRaises(ValueError):
            silver_configuration({"WEATHER_REQUIRE_SILVER": "yes"})

    def test_second_query_start_failure_cleans_up_first(self):
        redis = Mock(name="redis")
        redis.isActive = True
        with self.assertLogs("common.weather_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "HDFS permission denied"):
                supervise(Mock(), [lambda: redis, Mock(side_effect=RuntimeError("HDFS permission denied"))])
        redis.stop.assert_called_once()

    def test_runtime_failure_cleans_up_both(self):
        redis, silver = Mock(), Mock()
        for name, query in (("weather_latest", redis), ("weather_latest_silver", silver)):
            query.name = name
            query.isActive = True
        redis.exception.return_value = None
        silver.exception.return_value = RuntimeError("disk full")
        streams = Mock()
        streams.awaitAnyTermination.return_value = True
        with self.assertLogs("common.weather_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "weather_latest_silver: disk full"):
                supervise(streams, [lambda: redis, lambda: silver])
        redis.stop.assert_called_once()
        silver.stop.assert_called_once()

    def test_unexpected_stop_is_failure(self):
        query = Mock()
        query.name = "weather_latest"
        query.isActive = False
        query.exception.return_value = None
        streams = Mock()
        streams.awaitAnyTermination.return_value = True
        with self.assertLogs("common.weather_runtime", "ERROR"):
            with self.assertRaisesRegex(RuntimeError, "stopped unexpectedly"):
                supervise(streams, [lambda: query])

    def test_progress_is_logged_before_driver_failure(self):
        query = Mock()
        query.name = "weather_latest_silver"
        query.isActive = True
        query.lastProgress = {"batchId": 8, "numInputRows": 14, "durationMs": {"triggerExecution": 150}}
        streams = Mock()
        streams.awaitAnyTermination.side_effect = [False, RuntimeError("driver failed")]
        with self.assertLogs("common.weather_runtime", "INFO") as logs:
            with self.assertRaisesRegex(RuntimeError, "driver failed"):
                supervise(streams, [lambda: query])
        self.assertTrue(any("query=weather_latest_silver batch=8 inputRows=14" in x for x in logs.output))
        query.stop.assert_called_once()

if __name__ == "__main__":
    unittest.main()
