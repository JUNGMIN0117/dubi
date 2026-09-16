"""Spark integration for raw archive preservation and eventId conflict policy."""
import importlib.util
import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@unittest.skipUnless(importlib.util.find_spec("pyspark"), "pyspark runtime not installed")
class BusSilverSparkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from pyspark.sql import SparkSession
        cls.spark = SparkSession.builder.master("local[1]").appName("test_bus_silver").config("spark.sql.shuffle.partitions", "1").getOrCreate()

    @classmethod
    def tearDownClass(cls):
        cls.spark.stop()

    def test_archive_raw_rows_conflicts_duplicates_and_missing_time(self):
        import json
        from batch.bus_silver import prepare
        good = dict(eventId="p1", eventType="bus.position", source="seoul.bus_location", collectedAt="2026-09-16T08:10:05+09:00", occurredAt="2026-09-16T08:10:00+09:00", payload=dict(providerNamespace="SEOUL_BUS", providerRouteId="00124", providerVehicleId="V123", timestampBasis="received", raw={"gpsX": "126.978", "gpsY": "37.566"}))
        conflict = dict(good, eventId="p2")
        changed = dict(conflict, payload=dict(conflict["payload"], raw={"gpsX": "127.1", "gpsY": "37.5"}))
        values = [json.dumps(x) for x in (good, good, conflict, changed)] + ["{broken", json.dumps(dict(eventType="weather.observation"))]
        archive = self.spark.createDataFrame([(v, 0, i, datetime(2026, 9, 16, 8, 10)) for i, v in enumerate(values)], "value string, kafka_partition int, kafka_offset long, kafka_timestamp timestamp")
        parsed, checked = prepare(archive)
        self.assertEqual(parsed.count(), 6)
        self.assertEqual(checked.filter("repeatIndex > 1").count(), 1)
        unique = checked.filter("repeatIndex = 1")
        self.assertEqual(unique.filter("status = 'ACCEPTED'").count(), 1)
        self.assertEqual(unique.filter("status = 'REJECTED'").count(), 3)
        row = unique.filter("status = 'ACCEPTED'").first()
        self.assertIsNone(row.observedAt)
        self.assertEqual(row.providerRouteId, "00124")


if __name__ == "__main__":
    unittest.main()
