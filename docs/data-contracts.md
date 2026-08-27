# Data contracts

이 문서는 사람이 읽는 전체 계약 설명입니다. 기계 검증용 계약은 `docs/contracts/`에 둡니다.

## 공통 원칙

- 이벤트 시간은 ISO 8601과 Asia/Seoul 오프셋을 사용합니다.
- 각 이벤트는 중복 제거를 위한 `eventId`를 가집니다.
- 원본 발생 시각 `occurredAt`과 수집 시각 `collectedAt`을 구분합니다.
- 스키마 변경은 호환성을 검토하고 버전을 올립니다.
- API 키와 개인정보는 이벤트 payload에 넣지 않습니다.

## 계약 파일

- `contracts/events/*.schema.json`: Kafka 이벤트 JSON Schema
- `contracts/topics.yaml`: 토픽명·파티션·보존 정책
- `contracts/redis-keys.yaml`: Redis 키 형식과 TTL
