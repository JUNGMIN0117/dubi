"""보관된 city.raw TOPIS 공지를 현재 AI/지오코딩 규칙으로 일괄 전처리한다."""
import argparse
import os
from datetime import date

from pyspark.sql import SparkSession, functions as F

from common.archive_source import read_archive_events
from common.topis_enrichment import GmsClient, KakaoGeocoder, normalize_notice
from streaming.topis_enriched import SCHEMA


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Archive root, for example hdfs://.../ddubi/archive")
    parser.add_argument("--output", required=True, help="New versioned topis-backfill output root")
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("start must not be after end")
    if "topis-backfill/" not in args.output:
        parser.error("output must be a new versioned topis-backfill/<version> root")

    spark = SparkSession.builder.appName("topis_silver_backfill").config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    events = notices = None
    try:
        root = args.output.rstrip("/")
        spark.createDataFrame([(args.input, args.start.isoformat(), args.end.isoformat())],
                              ["input", "start", "end"]).write.mode("errorifexists").json(root + "/_run")
        events = read_archive_events(spark, args.input, "city.raw", args.start, args.end).cache()
        # Collector 중복 제거가 도입되기 전 아카이브에는 같은 공지가 반복된다.
        # 공지 ID+내용 해시가 같은 행은 AI를 한 번만 호출하고, 내용이 바뀐 개정본은 남긴다.
        notices = (events.filter(F.col("eventType") == "topis.notice")
            .withColumn("providerNoticeId", F.coalesce(
                F.get_json_object("rawEventJson", "$.payload.providerNoticeId"),
                F.get_json_object("rawEventJson", "$.payload.raw.noticeId")))
            .withColumn("noticeContentHash", F.coalesce(
                F.get_json_object("rawEventJson", "$.payload.contentHash"),
                F.sha2(F.get_json_object("rawEventJson", "$.payload.raw"), 256)))
            .dropDuplicates(["providerNoticeId", "noticeContentHash"])
            .select("rawEventJson").cache())

        gms = GmsClient(os.environ.get("GMS_API_KEY"), os.environ.get("GMS_BASE_URL", "https://gms.ssafy.io/gmsapi/"),
                        os.environ.get("GMS_MODEL", "gpt-4o-mini"), int(os.environ.get("TOPIS_AI_TIMEOUT_SECONDS", "30")))
        geocoder = KakaoGeocoder(os.environ.get("KAKAO_REST_API_KEY"))
        rows = []
        import json
        for item in notices.toLocalIterator():
            rows.extend(normalize_notice(json.loads(item.rawEventJson), gms, geocoder))
        result = spark.createDataFrame(rows, SCHEMA).withColumn("eventDate", F.to_date("collectedAt"))
        output_count = result.count()
        if output_count == 0:
            raise RuntimeError("No TOPIS notices found in the selected archive date range")
        result.write.mode("errorifexists").partitionBy("processingStatus", "eventDate").parquet(root + "/data")
        manifest = result.groupBy("processingStatus").count()
        manifest.write.mode("errorifexists").json(root + "/manifest")
        spark.createDataFrame([("COMPLETE", output_count)], ["status", "outputRows"]).write.mode("errorifexists").json(root + "/complete")
    finally:
        if notices is not None:
            notices.unpersist()
        if events is not None:
            events.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
