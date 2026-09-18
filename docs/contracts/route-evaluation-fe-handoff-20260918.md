# FE 전달: 일정 상세·경로 예상 표시 수정

대상: Flutter 담당자. 이번 Backend 변경에서는 Dart 파일을 수정하지 않았다.
기준 브랜치: `feature/backend/S15P21E202-74-develop-followup`.
이 문서는 2026-09-18 화면 제보에 대한 구현 지침이다. v13 서비스 연동이나 운영 승인을 의미하지 않는다.

## 1. 이번에 달라진 서버 계약

기존 `schedule-evaluation-v2`에 `guidance`, `candidateEstimates`를 추가했다.
기계 계약은 `docs/contracts/schedule-evaluation-v2.schema.json`이다.
기존 필드를 삭제하지 않았으며 구버전 저장 결과·응답에는 신규 필드가 없을 수 있다.

`quality.preview`와 `quality.productionEligible`는 ML 검증 계약이다.
이 두 값만으로 기본 경로 예상이나 출발 권고를 숨기지 않는다.
미승인 ML을 실제 시간에 적용한 결과는 `guidance.canAccept=false`다.
SHADOW 모델을 조회만 하고 카카오 시간을 사용한 결과는 기본 경로 예상으로 안내 가능하다.

응답 추가 부분 예시(전체 응답이 아닌 발췌, 식별자와 시각은 합성):

```json
{
  "evaluationId": "example-evaluation",
  "decision": "KEEP_DEPARTURE",
  "durationSeconds": 2640,
  "selectedRouteId": 101,
  "proposedRouteId": 101,
  "guidance": {
    "canDisplayEstimate": true,
    "canAccept": true,
    "source": "PROVIDER_ESTIMATE",
    "reasons": []
  },
  "candidateEstimates": [
    {
      "candidateKey": "candidate-1",
      "routeId": 101,
      "rank": 1,
      "assessment": "ON_TIME_ESTIMATE",
      "departAt": "2026-09-18T01:30:00Z",
      "estimatedArrivalAt": "2026-09-18T02:14:00Z",
      "durationSeconds": 2640,
      "selected": true,
      "proposed": true
    }
  ]
}
```

`candidateEstimates`는 **같은 평가, 같은 제안 출발시각**의 비교 결과다.
ETA 오름차순이며 계산 불가·통제 후보는 뒤에 온다. 이런 후보의 duration/ETA는 null이다.
`READ_ONLY`는 DB routeId가 null이다. candidateKey를 DB routeId로 변환하거나 배열 index로 추측하지 않는다.
현재 선택은 `selected`, 새 제안은 `proposed`로 구별한다. 사용자 수락 전 두 경로가 다를 수 있다.

## 2. 파일별 수정 지침

| 파일 | 수정할 부분 | 구현 방법 |
|---|---|---|
| `client/lib/domain/schedule_evaluation.dart` | v2 JSON 파싱·수락 판정 | `EvaluationGuidance`, `CandidateEstimate` 모델을 추가하고 nullable로 파싱한다. `canAcceptAt`에서 신규 guidance가 있으면 canAccept와 기존 상태/만료/입력 버전/출발시각 검사를 함께 사용한다. productionEligible을 추가 AND 조건으로 넣지 않는다. |
| `client/lib/presentation/schedule_evaluation_card.dart` | 상단 판정·이동 예측·적용 버튼 | guidance.canDisplayEstimate가 true이고 ETA가 있으면 decision에 맞는 예상 안내를 표시한다. preview 때문에 일괄적으로 “확정하지 못했어요”를 보여주는 분기를 대체한다. 적용 버튼은 canAcceptAt을 사용한다. |
| `client/lib/presentation/screens.dart` | 일정 상세 현재 선택·저장 후보·독립 경로 화면 | 현재 평가의 candidateEstimates를 routeId로 저장 후보와 결합한다. 평가의 순위와 durationSeconds를 우선 표시하고, 저장 원본 값은 “기본 경로 정보”로 구분한다. 별도 경로 화면의 로컬 역산은 서버 v2 조회 결과로 대체한다. |
| `client/lib/services/recommendation_api.dart` | 저장 경로 DTO·평가 응답 연결 | 기존 baseDurationMin/rankNo를 덮어쓰지 않는다. 평가 결과는 별도 필드/모델로 둔다. 선택된 저장 경로를 무조건 “선택된 추천 경로”라고 부르는 문구를 분리한다. |
| `client/lib/domain/evaluation_diagnostics.dart` | ML·실시간·결측 요약 | 모델 호출/수신/실제 적용을 구별한다. `segments.durationSource`와 실제 적용 사유로 판정한다. 단순 modelVersion 존재나 source 결측 건수로 ML 적용 여부를 추정하지 않는다. |
| `client/lib/presentation/evaluation_diagnostics_panel.dart` | 원본 진단 출력 | 개발자 모드에서만 원본 코드·ID를 출력한다. 일반 화면에는 원본 BUS/SUBWAY ID, 버전, raw MISSING 목록을 내보내지 않는다. |
| `client/lib/core/diagnostics/developer_diagnostics.dart` | 진단 기본 노출 | debug 빌드도 기본은 꺼짐으로 바꾸고 개발자 옵션을 명시적으로 켰을 때만 표시한다. 테스트 배포에서 디버그 빌드가 일반 사용자에게 전달되는 경우를 포함한다. |

### 수락 버튼 의사 코드

```text
새 guidance가 있으면:
  canAccept = guidance.canAccept
              && state == READY
              && evaluationId와 일정 입력 버전이 현재 값과 동일
              && now < validUntil
              && proposedDepartAt >= now
              && 미출발·미취소·미완료
없으면:
  구버전 수락 정책 유지(승인 여부를 새로 추측하지 않음)
```

서버는 같은 검사를 다시 수행한다. 409/만료/입력 변경 응답이면 재조회 후 안내한다.
날짜는 ISO 8601을 파싱하고 화면에서는 KST로 표시한다. 서버 durationSeconds는 초다.
분 표시는 `ceil(seconds / 60)`을 사용하되 계산·합산은 서버 초 단위 결과를 그대로 쓴다.

## 3. 화면별 문구와 표시

| 상태 | 사용자 안내 |
|---|---|
| KEEP_DEPARTURE + 기본 예상 | “입력한 출발시각으로 도착할 수 있을 것으로 예상돼요.” 출발·ETA·소요시간 표시 |
| ADVANCE_DEPARTURE | “목표보다 10분 일찍 도착하려면 {시각} 출발을 권해요.” 탐색 한계를 상세 안내에 보존 |
| MARGIN_UNAVAILABLE | 일정 시작 전 도착 예상은 있으나 10분 여유 확보는 어렵다고 표시 |
| LATE_EXPECTED | 가장 덜 늦는 제안의 ETA·예상 지각시간 표시. 정시 도착 가능 문구 금지 |
| SEARCH_INCOMPLETE | 탐색 미완료로 표시하되 bestKnownDecision과 확인된 ETA가 있으면 “현재까지 확인된 예상”으로 보여줌. 수락 차단 |
| DATA_INSUFFICIENT / ROUTE_UNAVAILABLE | 계산 불가와 사용 가능한 후보 없음 구분. 기본 저장 경로가 있다고 추천 성공으로 표시하지 않음 |
| EXPIRE / 입력 변경 | 이전 예상임을 표시하고 다시 계산 제공. 만료를 단순히 “경로가 없다”로 바꾸지 않음 |

기본 경로 fallback 문구: “일부 구간은 기본 경로 예상시간을 사용했어요.”
실시간 정보 부족은 “확인되지 않았어요”이며 정상 운행을 의미하지 않는다.
시험 적용된 ML은 “시험 중인 예측”이고, SHADOW 조회만 한 결과에 “ML 예상시간 반영”을 표시하지 않는다.

## 4. 도보·시간 구성 표시

- `segments`의 ACCESS_WALK / RIDE / TRANSFER_WALK / EGRESS_WALK를 순서대로 표시한다.
- 서버가 생성한 접근 도보는 sequence=0일 수 있다. 배열 순서를 유지하고 1부터 연속이라고 가정하지 않는다.
- 새 도보는 원본 저장 구간에 없어서 fromName/toName이 null일 수 있다. 접근은 “출발지 → 승차 위치”, 이탈은 “하차 위치 → 목적지”로 표시한다.
- `KAKAO_WALKING_API`만 실제 도보 API 시간이 반영된 경우다. 호출 실패 또는 미분류 비용이 있으면 반영됐다고 표시하지 않는다.
- `PROVIDER_PATH_ENDPOINT`는 카카오 경로의 끝점이다. 공식 역 출입구 검증 성공으로 표시하지 않는다.
- `segments`는 이동 구간 목록이다. 별도 대기·미분류 시간 때문에 목록의 합이 최종 durationSeconds와 다를 수 있다. 목록 합으로 총시간을 덮어쓰지 않는다.
- 저장 route의 baseDurationMin은 카카오 원본 값이다. 보강된 최신 평가 시간과 값이 다른 것은 오류가 아니다.

## 5. 프론트 검증 항목

1. 원본 경로 fallback + quality.preview=true라도 guidance가 허용하면 예상 출발·ETA가 보인다.
2. 같은 응답에서 모델 productionEligible=false를 유지한다. 모델 승인이 되었다는 표시를 하지 않는다.
3. ML 시험 적용은 예상값을 표시하되 수락 버튼을 비활성화한다.
4. selectedRouteId와 proposedRouteId가 다를 때 현재 선택과 새 제안을 구분한다.
5. 후보 1의 기본시간 50분/평가시간 45분, 후보 2의 기본시간 40분/평가시간 55분 fixture에서 평가 순위를 따른다.
6. 계산 불가 후보 null duration을 0분으로 만들거나 최상단 추천하지 않는다.
7. 도보 sequence=0, 이름 null, API 실패, ACCESS_ONLY, BOTH를 각각 표시한다.
8. 일반 화면에서 원본 결측 ID·개발자 로그가 보이지 않는다. 개발자 옵션에서만 확인할 수 있다.
9. 응답 만료·409·일정 수정·다시 계산 중에는 이전 제안을 수락하지 못한다.
10. 신규 필드가 없는 기존 fixture도 파싱 오류 없이 표시한다.

주요 테스트 파일: `client/test/schedule_evaluation_test.dart`, `schedule_evaluation_card_test.dart`,
`schedule_evaluation_api_test.dart`, `evaluation_diagnostics_test.dart`, `home_evaluation_test.dart`.
새 계약 fixture를 추가하되 기존 fixture의 productionEligible을 임의로 true로 바꾸지 않는다.
