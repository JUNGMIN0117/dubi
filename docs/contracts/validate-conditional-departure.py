"""조건부 평가 FE 예제를 JSON Schema와 실제 Java DTO 산출물에 대조한다.

저장소 파일만 읽으며 서버/DB/외부 API를 호출하지 않는다. jsonschema==4.26.0이 필요하다.
Java 테스트 산출물이 있으면 결정별 예제와 구조/값을 비교하여 수작업 fixture의 drift를 막는다.
실제 HTTP 산출물은 현재시각을 쓰므로 값 비교 대신 schema 검증만 한다.
"""
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


def main():
    """고정 응답 13개와 선택적 HTTP 산출물을 검증하고 첫 불일치에서 실패한다."""
    contracts = Path(__file__).resolve().parent
    root = contracts.parent.parent
    read = lambda path: json.loads(path.read_text(encoding="utf-8"))
    schema = read(contracts / "schedule-evaluation-v2.schema.json")
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    responses = sorted((contracts / "examples/conditional-departure/responses").glob("*.json"))
    assert len(responses) == 13, "정책 8개와 실행 상태 5개의 예제가 필요합니다."
    for path in responses:
        value = read(path)
        validator.validate(value)
        generated = root / "backend/target/conditional-departure-fixtures" / path.name
        if generated.exists():
            assert value == read(generated), "Java DTO와 예제가 다릅니다: " + path.name
    http = root / "backend/target/conditional-departure-http.json"
    if http.exists():
        validator.validate(read(http))
    print("PASS: %d response fixtures, HTTP artifact=%s" % (len(responses), http.exists()))


if __name__ == "__main__":
    main()
