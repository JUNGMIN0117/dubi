"""검증된 segment 대응표로 Redis 최신값을 요청 시점에 조립한다."""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

FEATURE_VERSION = "bus-segment-feature-v1"
FEATURE_NAMES = (
    "busCongestionCode",
    "busOperationStateCode",
    "recentSegmentMedianSeconds",
    "recentSegmentSampleCount",
    "roadSpeedKph",
    "roadTravelSeconds",
    "roadCoverageRatio",
    "accidentCount",
    "closureCount",
    "assemblyCount",
    "protestCount",
    "temperatureC",
    "humidityPercent",
    "precipitationMm",
    "snowDepthCm",
    "windSpeedMps",
    "cityCongestionLevel",
    "areaPopulationMin",
    "areaPopulationMax",
    "nearbyPopulation",
)
SEGMENT_PATTERN = re.compile(r"^BUS:[A-Za-z0-9._-]{1,50}(?::[A-Za-z0-9._-]{1,50}){2}$")
SAFE_VERSION = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


@dataclass(frozen=True)
class OnlineFeatureSettings:
    enabled: bool
    host: str
    port: int
    password: str | None
    socket_timeout_seconds: float
    cache_ttl_seconds: int
    crosswalk_sha: str | None = None

    @classmethod
    def from_env(cls, env):
        enabled = str(env.get("ML_REDIS_LATEST_FEATURES_ENABLED", "false")).lower() in ("1", "true", "yes")
        timeout = float(env.get("ML_REDIS_TIMEOUT_SECONDS", "1.5"))
        ttl = int(env.get("ML_ONLINE_FEATURE_TTL_SECONDS", "900"))
        if timeout <= 0 or ttl <= 0:
            raise ValueError("online feature Redis timeout and TTL must be positive")
        crosswalk_sha = str(env.get("ML_BUS_CROSSWALK_SHA256", "")).lower().strip()
        if enabled and not re.fullmatch(r"[0-9a-f]{64}", crosswalk_sha):
            raise ValueError("ML_BUS_CROSSWALK_SHA256 must be set when online features are enabled")
        return cls(
            enabled,
            env.get("REDIS_HOST", "redis"),
            int(env.get("REDIS_PORT", "6379")),
            env.get("REDIS_PASSWORD") or None,
            timeout,
            ttl,
            crosswalk_sha or None,
        )


def _text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return value


def _json(value):
    value = _text(value)
    if not value:
        return None
    try:
        result = json.loads(value)
        return result if isinstance(result, dict) else None
    except (TypeError, ValueError):
        return None


def _instant(value):
    if isinstance(value, datetime):
        result = value
    elif isinstance(value, str):
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        raise ValueError("timezone-aware ISO timestamp required")
    if result.tzinfo is None:
        raise ValueError("timezone-aware ISO timestamp required")
    return result.astimezone(UTC)


def _number(value, minimum=None, maximum=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    result = float(value)
    if minimum is not None and result < minimum or maximum is not None and result > maximum:
        return None
    return result


def _integer(value, minimum=None):
    if isinstance(value, str) and re.fullmatch(r"-?\d+", value.strip()):
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int) or minimum is not None and value < minimum:
        return None
    return value


def _source_times(value, as_of, passage_at):
    try:
        observed = _instant(value.get("occurredAt") or value.get("observedAt") or value.get("_latestOrderAt"))
        collected = _instant(value["collectedAt"])
        valid_until = _instant(value["validUntil"])
        valid_from = _instant(value["validFrom"]) if value.get("validFrom") else None
    except (KeyError, TypeError, ValueError):
        return None
    if (
        observed > as_of
        or collected > as_of
        or collected < observed
        or valid_from is not None
        and passage_at < valid_from
        or passage_at >= valid_until
    ):
        return None
    return observed, collected, valid_until


class OnlineFeatureAssembler:
    def __init__(self, client, cache_ttl_seconds=900, now=lambda: datetime.now(UTC), expected_crosswalk_sha=None):
        self.client = client
        self.cache_ttl_seconds = int(cache_ttl_seconds)
        self.now = now
        self.expected_crosswalk_sha = expected_crosswalk_sha

    def _mapping(self, kind, master_version, segment_id, as_of, passage_at):
        active_key = f"ml:mapping:bus-{kind}:active:{master_version}"
        mapping_key = _text(self.client.get(active_key))
        if not mapping_key:
            return None, {"status": "ACTIVE_MAPPING_MISSING", "activeKey": active_key}
        if self.expected_crosswalk_sha:
            metadata = _json(self.client.get(mapping_key + ":meta"))
            if not metadata or metadata.get("crosswalkSha256") != self.expected_crosswalk_sha:
                return None, {"status": "CROSSWALK_SHA_MISMATCH", "mappingKey": mapping_key}
        value = _json(self.client.hget(mapping_key, segment_id))
        if value is None:
            return None, {"status": "SEGMENT_MAPPING_MISSING", "mappingKey": mapping_key}
        if value.get("segmentId") != segment_id or value.get("masterVersion") != master_version:
            return None, {"status": "MAPPING_IDENTITY_MISMATCH", "mappingKey": mapping_key}
        if value.get("mappingStatus") != "RESOLVED":
            return None, {
                "status": value.get("mappingStatus") or "INVALID",
                "mappingKey": mapping_key,
                "reason": value.get("mappingReason"),
            }
        if kind == "road":
            try:
                valid_from = _instant(value["roadPathValidFrom"])
                valid_until = _instant(value["roadPathValidUntil"])
                mapped_at = _instant(value["mappingAsOf"])
            except (KeyError, TypeError, ValueError):
                return None, {"status": "MAPPING_TIME_MISSING", "mappingKey": mapping_key}
            if (
                value.get("topisLinkNamespace") != "SEOUL_ACCINFO_LINK"
                or mapped_at > as_of
                or passage_at < valid_from
                or passage_at >= valid_until
            ):
                return None, {"status": "MAPPING_NOT_VALID_AT_PASSAGE", "mappingKey": mapping_key}
        return value, {"status": "RESOLVED", "mappingKey": mapping_key, "mappingVersion": value.get("mappingVersion")}

    def assemble(self, request):
        segment_id, master_version = request.get("segmentId"), request.get("masterVersion")
        if not isinstance(segment_id, str) or not SEGMENT_PATTERN.fullmatch(segment_id):
            raise ValueError("canonical bus segment required")
        if not isinstance(master_version, str) or not SAFE_VERSION.fullmatch(master_version):
            raise ValueError("safe master version required")
        as_of, passage_at = _instant(request.get("asOf")), _instant(request.get("passageAt"))
        if passage_at < as_of:
            raise ValueError("passageAt must not precede asOf")
        if as_of > self.now().astimezone(UTC) + timedelta(seconds=30):
            raise ValueError("asOf must not be in the future")

        mappings, mapping_provenance = {}, {}
        for kind in ("weather", "road", "city"):
            mappings[kind], mapping_provenance[kind] = self._mapping(
                kind, master_version, segment_id, as_of, passage_at
            )

        source_keys = {}
        weather = mappings["weather"]
        if weather and weather.get("nx") is not None and weather.get("ny") is not None:
            source_keys["weather"] = ["weather:latest:{}:{}".format(weather["nx"], weather["ny"])]
        road = mappings["road"]
        if road and isinstance(road.get("topisLinkIds"), list):
            source_keys["road"] = [f"traffic:latest:flow:{value}" for value in road["topisLinkIds"] if value]
        city = mappings["city"]
        if city and city.get("primaryProviderAreaId"):
            source_keys["city"] = ["city:latest:area:{}".format(city["primaryProviderAreaId"])]
        source_keys["bus"] = [f"ml:latest:bus-segment:{segment_id}"]

        flat_keys = [key for values in source_keys.values() for key in values]
        raw_values = self.client.mget(flat_keys) if flat_keys else []
        decoded = {key: _json(raw) for key, raw in zip(flat_keys, raw_values, strict=False)}
        features = {name: None for name in FEATURE_NAMES}
        provenance = {"mappings": mapping_provenance}
        valid_until_values = []

        weather_values = []
        for key in source_keys.get("weather", []):
            value = decoded.get(key)
            times = _source_times(value, as_of, passage_at) if value else None
            if times:
                payload = value.get("features") if isinstance(value.get("features"), dict) else {}
                features["temperatureC"] = _number(payload.get("temperatureC"), -80, 60)
                features["humidityPercent"] = _number(payload.get("humidityPercent"), 0, 100)
                features["precipitationMm"] = _number(payload.get("precipitationMm"), 0)
                features["windSpeedMps"] = _number(payload.get("windSpeedMps"), 0)
                # 현재 초단기실황에는 적설 깊이 항목이 없으므로 0으로 추정하지 않는다.
                weather_values.append(value)
                valid_until_values.append(times[2])
        if weather_values:
            value = weather_values[0]
            provenance["weather"] = {
                "key": source_keys["weather"][0],
                "eventId": value.get("eventId"),
                "source": value.get("source"),
                "occurredAt": value.get("occurredAt"),
                "collectedAt": value.get("collectedAt"),
                "validUntil": value.get("validUntil"),
            }

        valid_roads = []
        for key in source_keys.get("road", []):
            value = decoded.get(key)
            times = _source_times(value, as_of, passage_at) if value else None
            observation = value.get("observation") if value and isinstance(value.get("observation"), dict) else {}
            if times:
                speed = _number(observation.get("speedKph"), 0)
                travel = _number(observation.get("travelTimeSeconds"), 0)
                if speed is not None or travel is not None:
                    valid_roads.append((key, value, speed, travel))
                    valid_until_values.append(times[2])
        total_road_links = len(source_keys.get("road", []))
        if total_road_links:
            features["roadCoverageRatio"] = len(valid_roads) / total_road_links
        speeds = [row[2] for row in valid_roads if row[2] is not None]
        travels = [row[3] for row in valid_roads if row[3] is not None]
        if speeds:
            features["roadSpeedKph"] = sum(speeds) / len(speeds)
        if travels and len(travels) == total_road_links:
            features["roadTravelSeconds"] = sum(travels)
        if valid_roads:
            provenance["road"] = {
                "keys": [row[0] for row in valid_roads],
                "linkCount": total_road_links,
                "validLinkCount": len(valid_roads),
            }

        for key in source_keys.get("city", []):
            value = decoded.get(key)
            times = _source_times(value, as_of, passage_at) if value else None
            if times:
                features["cityCongestionLevel"] = (
                    value.get("congestionLevel") if isinstance(value.get("congestionLevel"), str) else None
                )
                features["areaPopulationMin"] = _integer(value.get("populationMin"), 0)
                features["areaPopulationMax"] = _integer(value.get("populationMax"), 0)
                valid_until_values.append(times[2])
                provenance["city"] = {
                    "key": key,
                    "eventId": value.get("eventId"),
                    "observedAt": value.get("observedAt"),
                    "collectedAt": value.get("collectedAt"),
                    "validUntil": value.get("validUntil"),
                }
                break

        bus_key = source_keys["bus"][0]
        bus = decoded.get(bus_key)
        bus_times = _source_times(bus, as_of, passage_at) if bus else None
        if bus_times and self.expected_crosswalk_sha:
            canonical = bus.get("canonicalMapping")
            if not isinstance(canonical, dict) or canonical.get("crosswalkSha256") != self.expected_crosswalk_sha:
                bus_times = None
        if bus_times:
            observation = bus.get("observation") if isinstance(bus.get("observation"), dict) else {}
            features["busCongestionCode"] = _integer(observation.get("congestionCode"))
            features["busOperationStateCode"] = _integer(observation.get("providerStateCode"))
            valid_until_values.append(bus_times[2])
            provenance["bus"] = {
                "key": bus_key,
                "eventId": bus.get("eventId"),
                "occurredAt": bus.get("occurredAt"),
                "collectedAt": bus.get("collectedAt"),
                "validUntil": bus.get("validUntil"),
            }

        computed = self.now().astimezone(UTC)
        valid_until = (
            min(valid_until_values + [computed + timedelta(seconds=self.cache_ttl_seconds)])
            if valid_until_values
            else computed + timedelta(seconds=60)
        )
        if valid_until <= computed:
            valid_until = computed + timedelta(seconds=60)
        result = {
            "segmentId": segment_id,
            "masterVersion": master_version,
            "featureVersion": FEATURE_VERSION,
            "asOf": as_of.isoformat(),
            "computedAt": computed.isoformat(),
            "validUntil": valid_until.isoformat(),
            "features": features,
            "missingFeatures": sorted(name for name, value in features.items() if value is None),
            "provenance": provenance,
        }
        # 과거 시점 재현 요청으로 현재 캐시를 되돌리지 않는다. 실제 현재 요청만 짧게 캐시한다.
        if abs((computed - as_of).total_seconds()) <= 30:
            ttl = max(1, min(self.cache_ttl_seconds, int((valid_until - computed).total_seconds())))
            key = f"ml:feature:bus-segment:{FEATURE_VERSION}:{master_version}:{segment_id}"
            self.client.setex(key, ttl, json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return result
