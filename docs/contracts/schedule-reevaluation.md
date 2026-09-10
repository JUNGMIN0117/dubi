# 일정 출발 전 재평가 — sched-v1

관련 Jira: S15P21E202-46. 사용자 입력 출발시각 저장부터 후보 재평가·제안 수락까지의 계약이다.
새 코드가 작성된 상태이며, 아래 검증 현황과 미연결 범위를 배포 완료로 해석하지 않는다.

## 사용자 흐름

1. 일정 등록/수정에서 출발 날짜·시각과 도착 목표를 입력한다.
2. `schedule.planned_depart_at`에 입력을 저장한다. `recommendation.depart_at`은 별도 제안이다.
3. 감시기가 후보 경로를 확보하고 입력한 시각에 출발했을 때의 도착을 계산한다.
4. 선택 경로가 늦거나 막히면 같은 출발시각의 대안을 먼저 찾고, 없으면 더 이른 출발 후보를 다시 계산한다.
5. 화면에서 제안 경로·출발·도착·계산시각을 확인한다. 적용 버튼을 누른 경우에만 입력 출발과 선택 경로를 바꾼다.
6. 실제 출발 확인, 일정 취소/완료 또는 목표 도착시각 경과 시 감시를 끝낸다.

사용자 여유시간은 추가하지 않는다. 미래 버스 도착을 보장하지 않는다. 기존 출발 입력 없는 일정과
반복 규칙에서 생성된 일정은 `DEPARTURE_REQUIRED`이며, 개별 일정에 출발 입력을 넣어야 감시한다.
반복 규칙 자체의 출발시각 일괄 생성은 이 변경에 포함하지 않는다.

## 시간과 계산

- 일정 생성/PATCH의 `plannedDepartAt`은 기존 일정 API와 같은 KST LocalDateTime이다.
  예: `2026-09-11T08:30:00`. 생략/명시적 null은 기존 클라이언트 호환을 위해 허용한다.
  도착시각보다 앞서야 한다. 응답에도 같은 필드를 반환한다.
- 평가 내부/결과 Instant는 ISO 8601 절대시각이다. 화면은 KST로 변환한다. 기간 단위는 초다.
- 등록/수정 후 첫 평가는 다음 감시기 실행에서 수행한다. 이후 기본은 출발 60~30분 전 10분,
  30~10분 전 5분, 10분 이내 1분이다. 60분보다 많이 남으면 출발 60분 전까지 기다린다.
  조기 출발 제안이 있으면 그 시각을 기준으로 다음 점검을 앞당긴다. 주기는 초기 실험 정책이다.
- 감시기 목록 조회 주기 15초와 카카오 재요청 주기는 다르다. 카카오 후보는 기본 900초 캐시하며
  입력 변경/만료/새 지각·통제 판정 때 갱신한다. 탐색하는 출발시각마다 API를 호출하지 않는다.
- 총 예상시간에서 step 합을 뺀 잔여시간은 대기·환승 위치가 미확정인 시간이다. 한 번만 더하며,
  각 step 진입 가능 시각 범위를 잔여시간만큼 넓혀 유효기간을 검사한다.
- 확정된 canonical edge 전체의 `observedSeconds`, `observedAt`, `observationCount`,
  `computedAt`, `validUntil`이 유효할 때 해당 이동 step 시간을 관측 합으로 교체한다.
  `delaySeconds`를 다시 더하지 않는다. 관측 시각은 300초 이내이고 진입 범위 전체가 유효기간 안이어야 한다.
- 부분 관측, 미매핑, Redis 장애, stale, 미래 이용은 API 시간 fallback과 `tentative=true`로 표시한다.
  검증된 기준선/ML의 운영 연결은 아직 포함하지 않는다. `confidence=LOW`, `buffer_min=0`,
  `percentile_used=null`이며 API 시간을 P50/P95나 도착 확률로 표시하지 않는다.
- 조기 출발 탐색은 기본 60초 간격, 최대 3600초 전, 최대 2000회 평가다. 현재시각 이전은 제안하지 않는다.
  각 시각에서 전체 타임라인을 다시 계산한다. 탐색 상한 도달은 `budgetExhausted=true`다.
  배차 시간에 따른 결과는 단조롭지 않으므로 단순 지각분 차감/이분탐색을 사용하지 않는다.

## 버스·사고·집회 경계

순수 평가기는 검증된 차량 도착 범위와 대기 구간을 입력받아 놓친 차량/다음 차량/환승을 계산한다.
그러나 현재 카카오 어댑터는 미래 출발 조회와 대기 분해를 제공하지 않는다. 따라서 실시간 버스 도착
API를 이 흐름에 자동 결합하지 않으며, API fallback 제안은 잠정 예상이다. 실제 대기 연결에는
승차 지점·방향·차량별 도착 범위·유효시간과 기존 API 대기 제거 계약이 먼저 필요하다.

사고/집회 좌표를 여기서 임의로 매칭하지 않는다. 수집·전처리에서 canonical 구간으로 매핑한
`segment.status`/Redis 상태를 사용한다. `blocked`가 실제 이용 시간과 겹칠 때 해당 후보를 제외한다.
겹칠 가능성만 있으면 정보 부족으로 표시한다. 행사 존재만으로 몇 분 지연을 만들지 않는다.
현재 위험은 후보별 `risks`로 제공한다. 대안은 카카오 후보 안에서만 찾는다.

## API

외부 경로는 모두 `/api/v1/schedules/{scheduleId}` 아래이며 인증·일정 소유권을 검사한다.

| 메서드/경로 | 의미 |
|---|---|
| POST `/recommendations` | 즉시 최신 Redis로 평가. body 생략 가능. `forceRealtime`도 후보 TTL을 무시하지 않음 |
| GET `/recommendations/latest` | `state`, 선택적으로 `result`, `nextCheckAt`, `lastError` 반환 |
| POST `/recommendations/accept` | `{"evaluationId":"저장된 평가 ID"}`만 수락 |
| POST `/departure` | 사용자 실제 출발 확인, 감시 종료 |

최근 상태는 `DEPARTURE_REQUIRED`, `PENDING`, `READY`, `STALE`, `FAILED`, `STOPPED`다.
`result`의 핵심 형태는 다음과 같다(설명용 축약이며 실제 응답에는 타임라인·근거가 추가된다).

```json
{
  "state": "READY",
  "result": {
    "evaluationId": "불변 평가 ID",
    "plannedDepartAt": "2026-09-11T00:00:00Z",
    "arriveBy": "2026-09-11T00:30:00Z",
    "checkedAt": "2026-09-10T23:55:00Z",
    "selectedKey": "candidate-0",
    "selected": {"assessment": "LATE_ESTIMATE", "tentative": true},
    "suggestion": {
      "proposed": {"journeyKey": "candidate-1", "departureAt": "2026-09-11T00:00:00Z",
        "estimatedArrivalAt": "2026-09-11T00:25:00Z", "tentative": true},
      "budgetExhausted": false
    }
  }
}
```

수락 시 입력 버전, 소유권, 평가 ID, 이벤트 revision, 취소/출발 여부를 검사한다.
60초보다 오래된 평가나 이미 지난 제안은 `STALE_RECOMMENDATION`으로 거절한다.
다른 경로가 더 빠르더라도 현재 선택이 정시 예상이면 변경 제안을 자동으로 만들지 않는다.

내부 트리거는 `POST /internal/recommendations/segment-changes`, body
`{"segmentIds":["BUS:1:2:3"]}`이며 기존 `X-DDUBI-API-KEY` 인증을 사용한다. 최대 1000개다.
Kafka 소비는 opt-in이며 같은 `segment.status`를 받아 영향받는 선택 경로 일정을 깨운다.
역조회는 `route_segment_edge → route_segment → 선택 route → UPCOMING schedule`이다.

## 저장·실패·알림

V5 migration은 출발 입력, nullable percentile, `schedule_evaluation` 작업 상태를 추가한다.
120초 lease와 입력 `updated_at`을 대조해 중복 실행/늦은 계산 덮어쓰기를 막는다.
외부 I/O는 트랜잭션 밖, 후보·추천·알림 대기 저장은 하나의 트랜잭션이다.
계산 중 새 이벤트는 다음 계산을 즉시 예약한다. 실패는 정제된 오류 코드와 60초 재시도 시각으로 남긴다.
원본 URL/키는 저장하거나 노출하지 않는다.

알림은 기존 NotificationService의 사용자 설정·억제키·실패 상태를 사용한다. 입력 변경/취소/만료된
결과의 대기 알림은 보내지 않는다. FCM exactly-once나 실패 푸시의 무한 재시도는 보장하지 않는다.
추천은 매번 별도 행으로 보존하며 캐시 후보와 증거 스냅샷으로 순수 평가를 재현할 수 있다.

## 실행과 검증

실행 설정과 테스트 명령은 [백엔드 실행 메모](../../backend/SCHEDULE_EVALUATION.md)를 따른다.
2026-09-10 현재 계산·관측·경로 회귀 테스트 20개 통과. MySQL/Redis 통합 테스트 5개는 작성했지만
로컬 Docker 엔진 연결 실패로 실행 시작 단계에서 실패했다. Flutter SDK 부재로 화면 빌드/실기기 검증은 미실행이다.
실제 카카오·FCM·Kafka 연동과 운영 주기/호출량 검증도 배포 전에 필요하다.
