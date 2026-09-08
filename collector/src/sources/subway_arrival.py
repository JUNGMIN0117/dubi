"""서울시 전체 지하철 실시간 도착정보를 JSON 파일로 저장한다.

서울 열린데이터광장의 ``서울시 지하철 실시간 도착정보(일괄)`` API를
사용한다. ``ALL`` 응답이 밝힌 전체 건수와 실제 목록 길이가 다르면 일부
데이터만 받은 것으로 보고 저장하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

API_URL_TEMPLATE = (
    "http://swopenapi.seoul.go.kr/api/subway/"
    "{api_key}/json/realtimeStationArrival/ALL/"
)
COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "subway_arrival.json"
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class SubwayArrivalError(RuntimeError):
    """API 요청, 응답 검증 또는 저장에 실패했을 때 발생한다."""


def load_env_file(path: Path = DEFAULT_ENV_PATH) -> None:
    """간단한 KEY=VALUE 형식의 환경 파일을 읽는다."""
    if not path.exists():
        return

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise SubwayArrivalError(f"{path}:{line_number}: KEY=VALUE 형식이 아닙니다")

        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if not _ENV_NAME.fullmatch(name):
            raise SubwayArrivalError(f"{path}:{line_number}: 환경변수 이름이 올바르지 않습니다")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def _response_metadata(data: dict[str, Any]) -> tuple[str, str, int]:
    """서울시 응답에서 결과 코드, 메시지, 전체 건수를 읽는다."""
    error_message = data.get("errorMessage")
    if not isinstance(error_message, dict):
        raise SubwayArrivalError("지하철 API 응답에 errorMessage가 없습니다")

    code = str(error_message.get("code", ""))
    message = str(error_message.get("message", ""))
    try:
        total = int(error_message["total"])
    except (KeyError, TypeError, ValueError) as exc:
        raise SubwayArrivalError("지하철 API 응답의 전체 건수가 올바르지 않습니다") from exc
    return code, message, total


def fetch_all_subway_arrivals(
    api_key: str,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """서울시 전체 역의 실시간 도착정보를 JSON으로 조회하고 검증한다."""
    key = api_key.strip()
    if not key:
        raise SubwayArrivalError("SUBWAY_API_KEY가 비어 있습니다")

    # 인증키가 요청 URL의 경로에 들어가므로 경로 구분 문자 등을 안전하게 인코딩한다.
    api_url = API_URL_TEMPLATE.format(api_key=quote(key, safe=""))
    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(60.0, connect=5.0))
    try:
        response = http_client.get(
            api_url,
            headers={"Accept": "application/json"},
            # 운영 키의 ALL 요청은 생성된 전체 데이터 파일로 307 리다이렉트된다.
            follow_redirects=True,
        )
        if response.status_code != 200:
            # 인증키가 포함된 URL은 오류 메시지나 로그에 넣지 않는다.
            raise SubwayArrivalError(f"서울 지하철 도착 API가 HTTP {response.status_code}을 반환했습니다")
        try:
            data = response.json()
        except ValueError as exc:
            raise SubwayArrivalError("서울 지하철 도착 API 응답이 올바른 JSON이 아닙니다") from exc
        if not isinstance(data, dict):
            raise SubwayArrivalError("서울 지하철 도착 API JSON의 최상위 값이 객체가 아닙니다")

        code, message, total_count = _response_metadata(data)
        if code != "INFO-000":
            raise SubwayArrivalError(f"서울 지하철 도착 API 오류({code}): {message}")

        arrivals = data.get("realtimeArrivalList")
        if not isinstance(arrivals, list):
            raise SubwayArrivalError("지하철 API 응답의 realtimeArrivalList가 배열이 아닙니다")
        received_count = len(arrivals)
        if received_count != total_count:
            raise SubwayArrivalError(
                "서울시 전체 지하철 도착정보가 아닙니다: "
                f"전체 {total_count}건 중 {received_count}건 수신"
            )

        return {
            "collectedAt": datetime.now(timezone.utc).isoformat(),
            "totalCount": total_count,
            "receivedCount": received_count,
            "isComplete": True,
            "response": data,
        }
    except httpx.HTTPError as exc:
        raise SubwayArrivalError(
            f"서울 지하철 도착 API 연결에 실패했습니다: {type(exc).__name__}"
        ) from exc
    finally:
        if owns_client:
            http_client.close()


def save_json(data: dict[str, Any], output_path: Path) -> None:
    """검증된 응답을 UTF-8 JSON 파일로 원자적으로 저장한다."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(output_path)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="서울시 전체 지하철 실시간 도착정보를 JSON 파일로 저장"
    )
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_PATH, help="API 키 환경 파일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH, help="JSON 출력 경로")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_env_file(args.env_file)

    data = fetch_all_subway_arrivals(os.environ.get("SUBWAY_API_KEY", ""))
    save_json(data, args.output)
    print(
        f"지하철 도착정보 JSON 저장 완료: {args.output.resolve()} "
        f"({data['receivedCount']}건, 전체 수신={data['isComplete']})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
