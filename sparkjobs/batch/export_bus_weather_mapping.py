"""버스 segmentId와 현재 수집 중인 기상청 날씨 격자의 대응표를 Parquet으로 만든다."""

import argparse
import hashlib
import os

from pyspark.sql import SparkSession, types as T

from common.bus_crosswalk import load_crosswalk_edges
from common.bus_segment_mapping import build_bus_segment_edges
from common.bus_weather_mapping import (
    build_edge_weather_mapping,
    build_segment_weather_mapping,
    load_bus_stops,
)
from common.kma_grid import DEFAULT_WEATHER_GRID_POINTS, parse_weather_grid_points


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crosswalk", required=True)
    parser.add_argument("--crosswalk-sha", required=True)
    parser.add_argument("--bus-master", required=True)
    parser.add_argument("--bus-master-sha", required=True)
    parser.add_argument("--output", required=True, help="새 출력 디렉터리(HDFS 또는 file URI)")
    parser.add_argument(
        "--weather-grid-points",
        default=os.environ.get("WEATHER_GRID_POINTS") or DEFAULT_WEATHER_GRID_POINTS,
        help="Collector와 같은 지역명:nx:ny 목록",
    )
    args = parser.parse_args(argv)

    collected_pairs = parse_weather_grid_points(args.weather_grid_points)
    grid_config_sha = hashlib.sha256(
        ",".join("{}:{}".format(nx, ny) for nx, ny in collected_pairs).encode("utf-8")
    ).hexdigest()
    spark = SparkSession.builder.appName("ddubi-bus-weather-grid-mapping").getOrCreate()
    root = args.output.rstrip("/")
    try:
        verified_edges = load_crosswalk_edges(
            spark, args.crosswalk, args.crosswalk_sha, args.bus_master_sha
        )
        edges = build_bus_segment_edges(verified_edges)
        stops = load_bus_stops(
            spark, args.bus_master, args.bus_master_sha, verified_edges[1]
        )
        collected = spark.createDataFrame(
            collected_pairs,
            T.StructType([
                T.StructField("collectedNx", T.IntegerType(), False),
                T.StructField("collectedNy", T.IntegerType(), False),
            ]),
        )
        edge_mapping = build_edge_weather_mapping(edges, stops, collected).cache()
        segment_mapping = build_segment_weather_mapping(edge_mapping).cache()
        try:
            spark.createDataFrame(
                [(
                    "bus-weather-grid-v1", verified_edges[1], verified_edges[2],
                    args.bus_master_sha, grid_config_sha, len(collected_pairs),
                )],
                "mappingVersion string, masterVersion string, crosswalkSha256 string, "
                "busMasterSha256 string, collectedGridConfigSha256 string, collectedGridCount int",
            ).write.mode("errorifexists").json(root + "/_run")

            edge_mapping.write.mode("errorifexists").partitionBy(
                "providerNamespace"
            ).parquet(root + "/edges")
            segment_mapping.write.mode("errorifexists").parquet(root + "/mappings")
            edge_mapping.groupBy("mappingStatus", "gridRelation").count().write.mode(
                "errorifexists"
            ).json(root + "/quality/edges")
            segment_mapping.groupBy("mappingStatus", "gridRelation").count().write.mode(
                "errorifexists"
            ).json(root + "/quality/segments")

            edge_count = edge_mapping.count()
            segment_count = segment_mapping.count()
            resolved_count = segment_mapping.filter("mappingStatus = 'RESOLVED'").count()
            outside_count = segment_mapping.filter("mappingStatus = 'OUT_OF_COVERAGE'").count()
            unresolved_count = segment_mapping.filter(
                "mappingStatus IN ('UNRESOLVED', 'AMBIGUOUS')"
            ).count()
            spark.createDataFrame(
                [(
                    verified_edges[1], edge_count, segment_count, resolved_count,
                    outside_count, unresolved_count,
                )],
                "masterVersion string, edgeRows long, segmentRows long, resolvedRows long, "
                "outOfCoverageRows long, unresolvedOrAmbiguousRows long",
            ).write.mode("errorifexists").json(root + "/complete")
        finally:
            segment_mapping.unpersist()
            edge_mapping.unpersist()
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
