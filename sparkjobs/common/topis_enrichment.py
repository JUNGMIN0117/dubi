"""TOPIS notice AI extraction and Kakao geocoding; pure Python, shared by live/backfill."""
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timedelta, timezone

import requests

KST = timezone(timedelta(hours=9))
VERSION = "topis-enrichment-v1"
PROMPT_VERSION = "topis-extraction-v1"
ALLOWED_EVENTS = {"ASSEMBLY", "MARCH", "EVENT", "ROAD_CONTROL", "OTHER"}
ALLOWED_IMPACTS = {"VEHICLE_CONTROL", "DETOUR", "NON_STOP", "LANE_CONTROL", "NONE", "UNKNOWN"}
ROLES = {"START", "WAYPOINT", "END", "AFFECTED_AREA"}


class EnrichmentError(RuntimeError):
    pass


def content_hash(raw):
    return hashlib.sha256(json.dumps(raw, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class GmsClient:
    def __init__(self, api_key, base_url="https://gms.ssafy.io/gmsapi/", model="gpt-4o-mini", timeout=30):
        if not api_key:
            raise ValueError("GMS_API_KEY is required")
        self.url = base_url.rstrip("/") + "/api.openai.com/v1/chat/completions"
        self.key, self.model, self.timeout = api_key, model, timeout

    def extract(self, notice):
        schema = {"events": [{"eventType": "ASSEMBLY|MARCH|EVENT|ROAD_CONTROL|OTHER", "impactType": "VEHICLE_CONTROL|DETOUR|NON_STOP|LANE_CONTROL|NONE|UNKNOWN", "eventStatus": "PLANNED|ACTIVE|ENDED|UNKNOWN", "startDateText": None, "startTimeText": None, "endDateText": None, "endTimeText": None, "roadNames": [], "routePoints": [{"order": 1, "placeText": "", "role": "START|WAYPOINT|END|AFFECTED_AREA"}], "affectedBusRoutes": [], "affectedBusStops": [], "evidenceText": ""}]}
        prompt = ("다음 서울 TOPIS 공지에서 교통 영향 행사 정보를 추출하라. 원문에 없는 값은 null/빈 배열로 두고, "
                  "장소는 이동 순서대로 유지하며 근거 문장은 원문 그대로 써라. JSON만 반환하라. 스키마 예: "
                  + json.dumps(schema, ensure_ascii=False) + "\n입력: " + json.dumps(notice, ensure_ascii=False))
        response = requests.post(self.url, timeout=self.timeout,
            headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"},
            json={"model": self.model, "temperature": 0, "response_format": {"type": "json_object"},
                  "messages": [{"role": "system", "content": "너는 교통 공지 정보 추출기다."}, {"role": "user", "content": prompt}]})
        if response.status_code != 200:
            raise EnrichmentError("GMS request failed: HTTP %s" % response.status_code)
        try:
            return json.loads(response.json()["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
            raise EnrichmentError("GMS response is not valid structured JSON") from exc


class KakaoGeocoder:
    URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
    def __init__(self, api_key, timeout=10):
        if not api_key:
            raise ValueError("KAKAO_REST_API_KEY is required")
        self.key, self.timeout, self.cache = api_key, timeout, {}

    def search(self, place_text):
        queries = ["서울특별시 " + place_text, place_text]
        for query in queries:
            if query in self.cache:
                result = self.cache[query]
            else:
                response = requests.get(self.URL, timeout=self.timeout,
                    headers={"Authorization": "KakaoAK " + self.key}, params={"query": query, "size": 5})
                if response.status_code != 200:
                    raise EnrichmentError("Kakao geocoder failed: HTTP %s" % response.status_code)
                result = response.json().get("documents", [])
                self.cache[query] = result
            seoul = [x for x in result if "서울" in (x.get("address_name", "") + x.get("road_address_name", ""))]
            candidates = seoul or result
            if candidates:
                item = candidates[0]
                return {"geocodeQuery": query, "matchedPlaceName": item.get("place_name"),
                        "kakaoPlaceId": item.get("id"), "latitude": float(item["y"]),
                        "longitude": float(item["x"]), "geocodeStatus": "MATCHED" if len(candidates) == 1 else "AMBIGUOUS",
                        "geocodeCandidateCount": len(candidates), "usableForBusMapping": len(candidates) == 1}
        return {"geocodeQuery": queries[-1], "matchedPlaceName": None, "kakaoPlaceId": None,
                "latitude": None, "longitude": None, "geocodeStatus": "NOT_FOUND",
                "geocodeCandidateCount": 0, "usableForBusMapping": False}


def _clean_text(value):
    return str(value).strip() if value is not None and str(value).strip() else None


def _parse_datetime(date_text, time_text, collected_at):
    if not date_text or not time_text:
        return None, False
    date_match = re.search(r"(?:(\d{4})\s*년\s*)?(\d{1,2})\s*월\s*(\d{1,2})\s*일", str(date_text))
    time_match = re.search(r"(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분)?", str(time_text))
    if not date_match or not time_match:
        return None, False
    base = datetime.fromisoformat(str(collected_at).replace("Z", "+00:00")).astimezone(KST)
    inferred = date_match.group(1) is None
    year = int(date_match.group(1) or base.year)
    try:
        result = datetime(year, int(date_match.group(2)), int(date_match.group(3)), int(time_match.group(1)), int(time_match.group(2) or 0), tzinfo=KST)
    except ValueError:
        return None, inferred
    if inferred and result < base - timedelta(days=45):
        result = result.replace(year=year + 1)
    return result.isoformat(), inferred


def normalize_notice(envelope, gms, geocoder):
    """Return one fixed-shape row per extracted event; errors are data, not job failures."""
    payload, rows = envelope.get("payload") or {}, []
    raw = payload.get("raw") if isinstance(payload.get("raw"), dict) else {}
    notice_id = _clean_text(raw.get("noticeId") or payload.get("providerNoticeId"))
    base = {"silverVersion": VERSION, "noticeId": notice_id, "sourceEventId": envelope.get("eventId"),
            "source": envelope.get("source"), "title": _clean_text(raw.get("title")),
            "contentText": _clean_text(raw.get("contentText")), "categoryName": _clean_text(raw.get("categoryName")),
            "createdAtText": _clean_text(raw.get("createdAt")), "updatedAtText": _clean_text(raw.get("updatedAt")),
            "collectedAt": envelope.get("collectedAt"), "noticeContentHash": content_hash(raw),
            "extractionModel": getattr(gms, "model", None), "promptVersion": PROMPT_VERSION}
    if not notice_id or not base["contentText"] or not base["collectedAt"]:
        return [{**base, "eventId": None, "processingStatus": "REJECTED", "rejectionReasons": ["MISSING_NOTICE_ID_OR_TEXT_OR_COLLECTION_TIME"], "qualityFlags": [], "missingFields": [], "routePoints": [], "roadNames": [], "affectedBusRoutes": [], "affectedBusStops": []}]
    try:
        extracted = gms.extract({k: base[k] for k in ("noticeId", "title", "contentText", "collectedAt")})
        events = extracted.get("events")
        if not isinstance(events, list) or not events:
            raise EnrichmentError("GMS events must be a non-empty array")
    except Exception as exc:
        return [{**base, "eventId": notice_id + "-error", "processingStatus": "INVALID_AI_RESPONSE", "rejectionReasons": [type(exc).__name__], "qualityFlags": [], "missingFields": [], "routePoints": [], "roadNames": [], "affectedBusRoutes": [], "affectedBusStops": []}]
    for index, event in enumerate(events, 1):
        reasons, flags = [], []
        event_type, impact = event.get("eventType"), event.get("impactType")
        if event_type not in ALLOWED_EVENTS: reasons.append("INVALID_EVENT_TYPE")
        if impact not in ALLOWED_IMPACTS: reasons.append("INVALID_IMPACT_TYPE")
        start, inferred1 = _parse_datetime(event.get("startDateText"), event.get("startTimeText"), base["collectedAt"])
        end, inferred2 = _parse_datetime(event.get("endDateText") or event.get("startDateText"), event.get("endTimeText"), base["collectedAt"])
        if start is None: flags.append("START_TIME_UNAVAILABLE")
        if end is None: flags.append("END_TIME_UNAVAILABLE")
        if start and end and end <= start: reasons.append("END_NOT_AFTER_START")
        points = []
        raw_points = event.get("routePoints") if isinstance(event.get("routePoints"), list) else []
        for point_index, point in enumerate(raw_points, 1):
            place = _clean_text(point.get("placeText")) if isinstance(point, dict) else None
            if not place: continue
            role = point.get("role") if point.get("role") in ROLES else ("START" if point_index == 1 else "WAYPOINT")
            try: geo = geocoder.search(place)
            except Exception: geo = {"geocodeQuery": place, "matchedPlaceName": None, "kakaoPlaceId": None, "latitude": None, "longitude": None, "geocodeStatus": "ERROR", "geocodeCandidateCount": 0, "usableForBusMapping": False}
            points.append({"order": point_index, "placeText": place, "role": role, **geo})
        if not points: flags.append("ROUTE_POINTS_UNAVAILABLE")
        if any(not p["usableForBusMapping"] for p in points): flags.append("ROUTE_POINT_NOT_EXACT")
        status = "REJECTED" if reasons else ("PARTIAL" if flags else "VALID")
        row = {**base, "eventId": notice_id + "-" + str(index), "eventType": event_type, "impactType": impact,
               "eventStatus": event.get("eventStatus") or "UNKNOWN", "startAt": start, "endAt": end,
               "yearInferred": inferred1 or inferred2, "yearBasis": "COLLECTED_AT_YEAR" if inferred1 or inferred2 else "SOURCE_TEXT",
               "roadNames": event.get("roadNames") if isinstance(event.get("roadNames"), list) else [], "routePoints": points,
               "affectedBusRoutes": event.get("affectedBusRoutes") if isinstance(event.get("affectedBusRoutes"), list) else [],
               "affectedBusStops": event.get("affectedBusStops") if isinstance(event.get("affectedBusStops"), list) else [],
               "evidenceText": _clean_text(event.get("evidenceText")), "processingStatus": status,
               "rejectionReasons": reasons, "qualityFlags": flags}
        row["missingFields"] = [k for k in ("startAt", "endAt", "evidenceText") if not row.get(k)]
        rows.append(row)
    return rows
