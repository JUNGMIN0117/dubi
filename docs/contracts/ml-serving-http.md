# 버스 모델 수치 확인 HTTP 계약 v1

- Jira: S15P21E202-46 — 모델링 및 추천 연산·관련 데이터 계산 고도화
- 목적: GPU 서버에서 학습한 v12 모델을 로컬 CPU 서비스로 옮겨 실제 예측을 확인한다.
- 상태: **PREVIEW 전용**. 운영 추천에 사용할 승인된 BusPredictionPort 어댑터는 아니다.
- 원본 모델의 `production_ready=false`를 유지한다. 서비스 준비와 운영 승인은 별개다.
- 기존 Redis 상태·기준선 계약을 변경하지 않는다. Spring 추천은 기존 fallback을 유지한다.

## 주소와 호출

로컬 기준 `http://127.0.0.1:8000`, `POST /v1/bus/predictions:batch`.
실제 내부 DNS/배포 포트는 인프라 담당자가 정한다. 외부 공개 배포는 이 작업 범위가 아니다.
요청은 1~1000개 인접 구간 배열이다. 여러 경로도 서로 다른 `segment_key`로 한 번에 보낼 수 있다.
서비스는 요청 순서와 상관키를 보존하며 경로별 합산은 하지 않는다.

```json
{
  "request_id": "example-1",
  "segments": [{
    "segment_key": "route-1-edge-1",
    "provider_namespace": "SEOUL_BUS",
    "provider_route_id": "100100001",
    "provider_from_stop_id": "100000001",
    "provider_to_stop_id": "100000002",
    "from_stop_sequence": 1,
    "service_date": "2026-09-10",
    "hour_slot": 9
  }]
}
```

위 ID는 형식 예시이며 실제 인접성/학습 등록을 보장하지 않는다. 실측 이식 검증 입력은
`data/ml-serving-verification/request.json`에 별도 저장한다. ID는 문자열이며 정류장 ARS 번호,
카카오 ID, canonical segment_id를 대신 넣지 않는다. `from_stop_sequence`는 학습에 사용한
노선 내 순번(0~10000)이다. 경로 내 배열 인덱스가 아니다.

진입 예상 날짜와 시간은 Asia/Seoul 기준이다. 일정을 그대로 모든 구간에 복사하지 않고,
앞 구간의 도보·대기·이동을 반영해 계산해야 한다. 날짜는 YYYY-MM-DD, 시간대는 정수 0~23이다.

## 응답 의미

HTTP 200은 배치 처리가 완료됐다는 의미다. 개별 `status`와 `prediction`을 반드시 확인한다.

```json
{
  "request_id": "example-1",
  "model_version": "20260908-v12",
  "model_type": "lightgbm",
  "target": "hourly_mean_bus_segment_seconds",
  "production_ready": false,
  "bundle_production_ready": false,
  "supported_from": "2026-09-10",
  "supported_until": "2026-09-17",
  "computed_at": "2026-09-10T10:00:00+09:00",
  "results": [{
    "segment_key": "route-1-edge-1",
    "status": "PREVIEW",
    "reason": "MODEL_NOT_PRODUCTION_READY",
    "prediction": {
      "mean_seconds": 120.5,
      "missing_feature_count": 4,
      "missing_features": ["from_board_daily_hour_lag2month", "from_alight_daily_hour_lag2month", "to_board_daily_hour_lag2month", "to_alight_daily_hour_lag2month"],
      "unseen_id_count": 0,
      "history_sample_count": 8,
      "data_insufficient": false,
      "training_ids_verified": false
    }
  }]
}
```

- 수치는 설명용이다. `mean_seconds`는 시간대별 인접 버스 구간 평균 초이며 P50/P80/P95가 아니다.
- `history_sample_count`는 77~126일 전 같은 시간대 운행 이력의 관측 개수(최대 8)이다.
  학습 전체 행 수나 현재 운행 차량 수가 아니다. 0이면 `data_insufficient=true`다.
- 결측은 원본의 학습 중앙값 + 결측 표시로 변환한다. 실제 0으로 위장하지 않는다.
- vocab 등록은 개별 ID 확인일 뿐 노선·정류장·순번 조합의 정적 topology 검증은 아니다.
  따라서 `training_ids_verified`를 임의로 true로 올리지 않는다.
- `computed_at`은 계산 시각이다. 과거 원천의 발표 이력이 완전히 복원되지 않았으므로
  운영용 `featuresAsOf`, `validUntil`, 보정 신뢰도를 임의 생성하지 않는다.
- 지원 날짜는 현재 내려받은 참조 묶음 범위다. 이후 날짜는 새 자료 묶음 생성·검증이 필요하다.

## 실패·동시 실행·fallback

| 상황 | HTTP | 개별 결과/설명 |
|---|---|---|
| 경기·인천 등 미지원 출처 | 200 | UNAVAILABLE, UNSUPPORTED_PROVIDER, prediction=null |
| 학습 vocab에 없는 ID | 200 | UNAVAILABLE, UNSEEN_PROVIDER_ID, prediction=null |
| 참조 지원 날짜 범위 밖 | 200 | UNAVAILABLE, REFERENCE_DATE_UNSUPPORTED, prediction=null |
| 잘못된 JSON/필수 값/범위/중복 상관키 | 422 | INVALID_REQUEST, 필드 위치·오류 종류 |
| 모델 미설치 또는 SHA 불일치 | 503 | MODEL_NOT_READY |
| 다른 배치 처리 중 | 503 | MODEL_BUSY |
| 참조 읽기 실패/결과 비정상 | 503 | MODEL_UNAVAILABLE |

요청마다 모델을 로드하지 않는다. 메모리 폭증 방지를 위해 프로세스당 배치 하나를 처리하고
CPU 스레드를 2개로 제한한다. 초기 호출 예산은 **연결 200ms, 전체 1초, 자동 재시도 없음**이라는
이전 제안이 있었으나, 아직 운영 SLA로 확정하지 않는다. 로컬 대조 도구로 실제 시간을 측정하고
콜드/반복 요청·실제 동시 부하를 검증한 뒤 Backend와 확정해야 한다.

2026-09-10 로컬 CPU 실측에서 10개 구간 일괄 호출은 2.07초/1.85초였으므로,
전체 1초 예산으로는 현재 구현이 완료되지 않는다. 수치 확인 시에는 클라이언트 전체
timeout 10초를 사용한다. 이는 운영 SLA가 아니며 현재 서버가 작업을 강제 중단하는
시간 제한도 아니다. 운영 연결 전에는 참조 로딩 최적화와 부하 측정이 필요하다.

PREVIEW 또는 UNAVAILABLE 응답은 운영 추천 시간을 바꾸지 않는다. 현재 기준선 커버리지는
사용자 확인상 0이므로 경로 API 시간이 기본이다. 향후 승인된 어댑터는 기존 통합 계약의
입력 지문·전체 edge·표본·유효시각을 만족해야 하며 한 step 일부 실패 시 step 전체 fallback한다.

## 실행·검증

자세한 명령과 실제 이식 검증은 `ml-serving/README.md`를 따른다.
`GET /health/live`는 프로세스 생존, `GET /health/ready`는 수치 확인 준비 상태다.
대화형 요청과 기계 스키마는 `/docs`, `/openapi.json`에서 확인한다.
