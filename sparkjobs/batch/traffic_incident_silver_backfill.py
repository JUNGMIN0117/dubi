"""Archived city.raw traffic incidents -> versioned Silver Parquet; Redis is not changed."""
import argparse
import json
import os
from datetime import date

from pyspark.sql import SparkSession, functions as F

from common.archive_source import read_archive_events
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident
from common.traffic_incident_policy import policy
from streaming.traffic_incident_silver import SCHEMA


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("start must not be after end")
    if "traffic-incident-backfill/" not in args.output:
        parser.error("output must be a new traffic-incident-backfill/<version> root")
    # 설정 오류가 부분 _run/output 디렉터리를 남기기 전에 먼저 실패시킨다.
    incident_policy = policy(os.environ)
    spark = SparkSession.builder.appName("traffic_incident_silver_backfill").config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    events = incidents = None
    try:
        root = args.output.rstrip("/")
        spark.createDataFrame([(args.input, args.start.isoformat(), args.end.isoformat())], ["input", "start", "end"]).write.mode("errorifexists").json(root + "/_run")
        events = read_archive_events(spark, args.input, "city.raw", args.start, args.end).cache()
        incidents = (events.filter(F.col("eventType") == "traffic.incident")
            .withColumn("incidentId", F.get_json_object("rawEventJson", "$.payload.providerIncidentId"))
            .withColumn("contentHash", F.get_json_object("rawEventJson", "$.payload.contentHash"))
            .dropDuplicates(["incidentId", "contentHash"]).select("rawEventJson").cache())
        master = VerifiedLinkMaster.load(os.environ.get("TRAFFIC_LINK_MASTER_PATH", "").strip(),
                                         os.environ.get("TRAFFIC_LINK_MASTER_SHA256", "").strip())
        rows = [
            normalize_incident(
                json.loads(item.rawEventJson),
                master,
                incident_policy.stale_seconds,
                incident_policy.direct_tolerance_meters,
                incident_policy.nearby_max_meters,
            )
            for item in incidents.toLocalIterator()
        ]
        result = spark.createDataFrame(rows, SCHEMA).withColumn("eventDate", F.to_date("collectedAt"))
        output_count = result.count()
        if not output_count:
            raise RuntimeError("No traffic incidents found in selected archive range")
        result.write.mode("errorifexists").partitionBy("processingStatus", "locationMatchType", "eventDate").parquet(root + "/data")
        result.groupBy("processingStatus", "locationMatchType").count().write.mode("errorifexists").json(root + "/manifest")
        spark.createDataFrame([("COMPLETE", output_count)], ["status", "outputRows"]).write.mode("errorifexists").json(root + "/complete")
    finally:
        if incidents is not None: incidents.unpersist()
        if events is not None: events.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
