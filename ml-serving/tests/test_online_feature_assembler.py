import json
import sys
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from online_feature_assembler import OnlineFeatureAssembler


class FakeRedis:
    def __init__(self):
        self.strings = {}
        self.hashes = {}

    def get(self, key):
        return self.strings.get(key)

    def hget(self, key, field):
        return self.hashes.get(key, {}).get(field)

    def mget(self, keys):
        return [self.strings.get(key) for key in keys]

    def setex(self, key, ttl, value):
        self.strings[key] = value
        self.strings[key + ":ttl"] = ttl


SEGMENT = "BUS:1:A:B"
MASTER = "master-v1"
NOW = datetime(2026, 9, 18, 1, 0, tzinfo=UTC)


def request(**changes):
    value = dict(
        segmentId=SEGMENT, masterVersion=MASTER, asOf="2026-09-18T10:00:00+09:00", passageAt="2026-09-18T10:10:00+09:00"
    )
    value.update(changes)
    return value


def source(**changes):
    value = dict(
        eventId="e1",
        source="fixture",
        occurredAt="2026-09-18T09:55:00+09:00",
        observedAt="2026-09-18T09:55:00+09:00",
        collectedAt="2026-09-18T09:56:00+09:00",
        validUntil="2026-09-18T10:30:00+09:00",
    )
    value.update(changes)
    return value


def configured():
    client = FakeRedis()
    mapping_keys = {
        "weather": "ml:mapping:bus-weather:v1:master-v1:sha",
        "road": "ml:mapping:bus-road:v1:master-v1:sha",
        "city": "ml:mapping:bus-city:v1:master-v1:sha",
    }
    for kind, key in mapping_keys.items():
        client.strings[f"ml:mapping:bus-{kind}:active:{MASTER}"] = key
    client.hashes[mapping_keys["weather"]] = {
        SEGMENT: json.dumps(
            dict(segmentId=SEGMENT, masterVersion=MASTER, mappingStatus="RESOLVED", mappingVersion="v1", nx=60, ny=127)
        )
    }
    client.hashes[mapping_keys["road"]] = {
        SEGMENT: json.dumps(
            dict(
                segmentId=SEGMENT,
                masterVersion=MASTER,
                mappingStatus="RESOLVED",
                mappingVersion="v1",
                topisLinkNamespace="SEOUL_ACCINFO_LINK",
                topisLinkIds=["L1", "L2"],
                mappingAsOf="2026-09-18T09:00:00+09:00",
                roadPathValidFrom="2026-09-01T00:00:00+09:00",
                roadPathValidUntil="2026-10-01T00:00:00+09:00",
            )
        )
    }
    client.hashes[mapping_keys["city"]] = {
        SEGMENT: json.dumps(
            dict(
                segmentId=SEGMENT,
                masterVersion=MASTER,
                mappingStatus="RESOLVED",
                mappingVersion="v1",
                primaryProviderAreaId="POI009",
            )
        )
    }
    client.strings["weather:latest:60:127"] = json.dumps(
        source(features={"temperatureC": 20.5, "humidityPercent": 70, "precipitationMm": 0, "windSpeedMps": 2.1})
    )
    client.strings["traffic:latest:flow:L1"] = json.dumps(source(observation={"speedKph": 24, "travelTimeSeconds": 70}))
    client.strings["traffic:latest:flow:L2"] = json.dumps(
        source(validUntil="2026-09-18T10:05:00+09:00", observation={"speedKph": 30, "travelTimeSeconds": 80})
    )
    client.strings["city:latest:area:POI009"] = json.dumps(
        source(congestionLevel="CROWDED", populationMin=12000, populationMax=14000)
    )
    client.strings[f"ml:latest:bus-segment:{SEGMENT}"] = json.dumps(
        source(observation={"congestionCode": "3", "providerStateCode": "1"})
    )
    return client


def test_latest_values_are_joined_and_expired_values_are_missing():
    client = configured()
    assembler = OnlineFeatureAssembler(client, now=lambda: NOW)
    result = assembler.assemble(request())
    assert result["features"]["temperatureC"] == 20.5
    assert result["features"]["roadSpeedKph"] == 24
    assert result["features"]["roadCoverageRatio"] == 0.5
    assert result["features"]["roadTravelSeconds"] is None
    assert result["features"]["cityCongestionLevel"] == "CROWDED"
    assert result["features"]["areaPopulationMin"] == 12000
    assert result["features"]["busCongestionCode"] == 3
    assert result["features"]["accidentCount"] is None
    assert "accidentCount" in result["missingFeatures"]
    assert client.get(f"ml:feature:bus-segment:bus-segment-feature-v1:{MASTER}:{SEGMENT}")


def test_future_collection_and_unresolved_mapping_do_not_enter_features():
    client = configured()
    weather_key = client.get(f"ml:mapping:bus-weather:active:{MASTER}")
    client.hashes[weather_key][SEGMENT] = json.dumps(
        dict(segmentId=SEGMENT, masterVersion=MASTER, mappingStatus="AMBIGUOUS", mappingReason="two grids")
    )
    client.strings["city:latest:area:POI009"] = json.dumps(
        source(
            collectedAt="2026-09-18T10:01:00+09:00", congestionLevel="CROWDED", populationMin=12000, populationMax=14000
        )
    )
    result = OnlineFeatureAssembler(client, now=lambda: NOW).assemble(request())
    assert result["features"]["temperatureC"] is None
    assert result["features"]["cityCongestionLevel"] is None
    assert result["provenance"]["mappings"]["weather"]["status"] == "AMBIGUOUS"


def test_valid_from_missing_values_and_historical_request_are_handled_without_fabrication():
    client = configured()
    client.strings["weather:latest:60:127"] = json.dumps(
        source(
            validFrom="2026-09-18T10:11:00+09:00",
            features={"temperatureC": 21.0, "humidityPercent": 60},
        )
    )
    client.strings.pop("city:latest:area:POI009")
    result = OnlineFeatureAssembler(client, now=lambda: NOW).assemble(
        request(
            asOf="2026-09-18T09:00:00+09:00",
            passageAt="2026-09-18T10:10:00+09:00",
        )
    )
    assert result["features"]["temperatureC"] is None
    assert result["features"]["cityCongestionLevel"] is None
    assert "temperatureC" in result["missingFeatures"]
    assert "cityCongestionLevel" in result["missingFeatures"]
    cache_key = f"ml:feature:bus-segment:bus-segment-feature-v1:{MASTER}:{SEGMENT}"
    assert client.get(cache_key) is None


def test_all_missing_sources_receive_only_short_result_validity():
    client = FakeRedis()
    result = OnlineFeatureAssembler(client, cache_ttl_seconds=900, now=lambda: NOW).assemble(request())
    assert result["validUntil"] == "2026-09-18T01:01:00+00:00"
    assert all(value is None for value in result["features"].values())
    assert result["provenance"]["mappings"]["city"]["status"] == "ACTIVE_MAPPING_MISSING"


def test_wrong_master_and_expired_road_mapping_are_not_used():
    client = configured()
    city_key = client.get(f"ml:mapping:bus-city:active:{MASTER}")
    client.hashes[city_key][SEGMENT] = json.dumps(
        dict(segmentId=SEGMENT, masterVersion="other-master", mappingStatus="RESOLVED", primaryProviderAreaId="POI009")
    )
    road_key = client.get(f"ml:mapping:bus-road:active:{MASTER}")
    road = json.loads(client.hashes[road_key][SEGMENT])
    road["roadPathValidUntil"] = "2026-09-18T10:09:00+09:00"
    client.hashes[road_key][SEGMENT] = json.dumps(road)
    result = OnlineFeatureAssembler(client, now=lambda: NOW).assemble(request())
    assert result["features"]["cityCongestionLevel"] is None
    assert result["features"]["roadSpeedKph"] is None
    assert result["provenance"]["mappings"]["city"]["status"] == "MAPPING_IDENTITY_MISMATCH"
    assert result["provenance"]["mappings"]["road"]["status"] == "MAPPING_NOT_VALID_AT_PASSAGE"


def test_future_as_of_is_rejected():
    import pytest

    with pytest.raises(ValueError, match="future"):
        OnlineFeatureAssembler(configured(), now=lambda: NOW).assemble(
            request(asOf="2026-09-18T10:01:00+09:00", passageAt="2026-09-18T10:10:00+09:00")
        )


def test_updated_crosswalk_rejects_old_mapping_and_bus_observation():
    sha = "a" * 64
    client = configured()
    assembler = OnlineFeatureAssembler(client, now=lambda: NOW, expected_crosswalk_sha=sha)
    result = assembler.assemble(request())
    assert result["features"]["temperatureC"] is None
    assert result["features"]["cityCongestionLevel"] is None
    assert result["features"]["busCongestionCode"] is None
    assert result["provenance"]["mappings"]["weather"]["status"] == "CROSSWALK_SHA_MISMATCH"

    for kind in ("weather", "road", "city"):
        key = client.get(f"ml:mapping:bus-{kind}:active:{MASTER}")
        client.strings[key + ":meta"] = json.dumps({"crosswalkSha256": sha})
    bus_key = f"ml:latest:bus-segment:{SEGMENT}"
    bus = json.loads(client.strings[bus_key])
    bus["canonicalMapping"] = {"crosswalkSha256": sha}
    client.strings[bus_key] = json.dumps(bus)
    result = assembler.assemble(request())
    assert result["features"]["temperatureC"] == 20.5
    assert result["features"]["cityCongestionLevel"] == "CROWDED"
    assert result["features"]["busCongestionCode"] == 3


def test_ml_serving_endpoint_uses_injected_assembler():
    from app import create_app
    from fastapi.testclient import TestClient

    assembler = OnlineFeatureAssembler(configured(), now=lambda: NOW)
    with TestClient(create_app(predictor=object(), online_feature_assembler=assembler)) as client:
        response = client.post("/v1/bus/features:latest", json=request())
        assert response.status_code == 200
        assert response.json()["segmentId"] == SEGMENT
        invalid = client.post("/v1/bus/features:latest", json=request(asOf="2026-09-18T10:01:00+09:00"))
        assert invalid.status_code == 422


def test_ml_serving_feature_endpoint_is_unavailable_when_disabled(monkeypatch):
    from app import create_app
    from fastapi.testclient import TestClient

    monkeypatch.setenv("ML_REDIS_LATEST_FEATURES_ENABLED", "false")
    with TestClient(create_app(predictor=object())) as client:
        response = client.post("/v1/bus/features:latest", json=request())
        assert response.status_code == 503
        assert response.json()["detail"] == "FEATURE_STORE_NOT_READY"
