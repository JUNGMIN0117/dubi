# 버스 이력 독립 모델 v13 계약 및 검증 기록

## 상태와 범위

2026-09-18 사용자 제공 `KakaoTalk_Longtxt_20260918_0106_07_556.txt`와 구현 선택(2번)에 따른 변경이다.
기존 v12의 승인값/입력을 수정하는 방식 대신 새 모델을 학습하고 별도 v2 API로 연결한다.
현재 **로컬 코드 검증 및 실제 서버 재학습·평가·번들 생성 완료** 상태다.
로컬 실모델 설치·저장된 카카오 매핑 결과의 커버리지·Backend 실호출 검증도 완료했다.
GPU 0 학습은 LightGBM CUDA illegal memory access로 중단되었다. 같은 서버에서 CPU
최대 16개를 사용한 별도 실행 `20260918-v13-static-cpu`를 완료했다. 운영 배포 완료가 아니다.

이 변경의 대상은 `SEOUL_BUS` 인접 정류장 사이 **시간대별 평균 이동시간(초)**다.
버스 대기, 지하철 시간표/실시간 계산, 환승, 도보, 전체 경로의 실제 도착시간을 학습하지 않는다.
지하철 ML 모델 부재가 버스 모델의 과거 Parquet 조회를 발생시키는 것은 아니다.

## 입력과 데이터 출처

| 구분 | 위치 / 출처 | 사용하는 값 | 사용하지 않는 값 / 한계 |
|---|---|---|---|
| 기존 정규화 학습 표본 | GPU `/home/j-j15e202/data/normalized/20260908-v12/model_features/*.parquet` | `service_date`, `provider_route_id`, `provider_from_stop_id`, `provider_to_stop_id`, `from_stop_sequence`, `hour_slot`, `target_seconds` | 기존 lag·수요·좌표 컬럼은 읽지 않음. 기존 샘플링/정규화 산출물 재사용이며 원본 전량 학습이 아님 |
| 정답 원천 | 기존 v12의 서울 OA-21217 버스 구간 시간 자료 | `target_seconds`: 시간대별 평균 초 | 개별 차량 통행 실측과 섞지 않음. 원천 공개시각/과거 노선 변경을 새로 입증한 것은 아님 |
| 공식 현재 마스터 | `backend/transit-master/bus/metropolitan-bus-v6.json.gz`, 버전 `METRO-BUS-20260917-SEOUL-0902` | 서울 노선의 `routeId`, 정류장 `stopId`, `sequence`, `direction` | GBIS/인천은 v13 지원 대상 아님. 최신 마스터를 과거 시점에도 유효했다고 주장하지 않음 |
| 공휴일 | 학습 환경의 `holidays.KR` 결과를 `input_spec.json`에 날짜 목록으로 고정 | 해당 날짜의 공휴일 여부 | 서빙 중 외부 달력 조회 없음. 기술적 지원일 밖은 거절 |
| 요청 | Backend의 검증된 canonical BUS edge | 서울 provider ID, 노선 내 순번, 앞 구간 시간을 누적한 KST 진입 날짜/시각 | canonical 문자열을 잘라 provider ID를 만들지 않음 |

GPU에서 확인한 기존 표본은 1,476개 날짜 파일, 100,027,638행이다. 파일 범위는
2022-01-03~2026-08-23이다. 이 수치는 새 학습의 품질 필터 적용 전 파일 메타데이터 합계다.
최종 사용 행 수·제외 수·파일별 SHA는 새 `source_usage.json`으로 확정해야 한다.

문서에 언급된 server2 MySQL 10,020,903행과 주간/월간 후보 데이터는 이번 학습 입력에
추가하지 않았다. 확보·중복 제거·평균/실측 정답 의미가 검증되지 않은 데이터를
기존 정답에 합치지 않는다. 이 문서는 해당 서버 데이터까지 검증 완료했다는 뜻이 아니다.

## 새 특성 및 학습 방법

공통 변환은 `ml-serving/static_bus_features.py`다. 총 10개 특성을 순서대로 고정한다.

```text
provider_route_id, provider_from_stop_id, provider_to_stop_id,
from_stop_sequence, hour_slot, spark_dow, is_weekend,
month, day_of_year, is_public_holiday
```

ID vocab은 학습 구간에서만 생성한다. 미등록 ID는 평가 행렬에서 0으로 변환하여
일반화 오차를 측정할 수 있지만, 실제 서빙은 공식 현재 인접 조합과 학습 관측의 교집합만 허용한다.
좌표·직선거리·날씨·행사·lag·승하차·운행횟수는 모델 입력에 없다.
정류장 좌표를 빼는 것은 금지된 직선거리와 혼동해서가 아니라 과거 좌표 시점 대응을
검증하지 않은 첫 request-only 모델의 명시적 선택이다.

기존 v12의 입력 명세는 ID 3개 + 수치 32개다. 수치별 missing indicator까지 만든
실제 모델 행렬은 67열이다. 새 모델에서 유지하는 10개 외에 다음 25개를 학습/서빙 양쪽에서 제외한다.

```text
from_latitude, from_longitude, to_latitude, to_longitude, coordinate_matched,
history_recent_seconds, history_median_4obs, history_mean_4obs,
history_median_8obs, history_mean_8obs, history_std_8obs,
history_count_4obs, history_count_8obs, history_trend_seconds,
from_board_daily_hour_lag2month, from_alight_daily_hour_lag2month,
to_board_daily_hour_lag2month, to_alight_daily_hour_lag2month,
passenger_recent, passenger_mean_4obs, passenger_std_4obs,
passenger_count_4obs, passenger_trend, boarding_lag77d, bus_runs_lag77d
```

제공 문서의 `missing_feature_count=15` 요청 원문은 확보되지 않아 정확한 15개 이름을
역으로 특정할 수 없다. 별도 기존 golden 입력(노선 `100100001`, 정류장
`100000004→101000001`, 순번 23, 2026-09-18 09시)을 현재 로컬 v12로 재현한 결과는
147.55초, history 8건, 결측 **4개**였다. 결측은 위 목록의 `*_daily_hour_lag2month`
4개이며 월별 승하차 자료의 매칭/공개시각 가용성 조건을 만족하지 못한 입력이다.
이 별도 결과를 문서의 15개 결측 사례를 재현했다고 표현하지 않는다.
해당 한 번의 로컬 cold 예측은 약 1.96초였으며 통계적 성능 벤치마크가 아니다.

`tools/train_static_bus.py`의 과정:

1. 필요한 정답/요청 컬럼만 읽고 파일 SHA와 원본 행 수를 기록한다.
2. ID 형식, 순번·시각 범위, 날짜 파일명 일치, 양수·유한 정답을 검증한다.
3. 같은 날짜·노선·정류장 쌍·순번·시간대의 상충 정답은 해당 키 전체 제외한다.
   같은 정답 중복은 한 건으로 줄인다. 큰 지연을 1,800초에서 임의로 자르지 않는다.
4. 날짜순 앞 70%를 학습, 뒤 30%를 최종 평가로 분리하고 실제 행 비율을 기록한다.
   학습 구간 내부 앞 80%/뒤 20%에서 early stopping 반복 수만 선택한다.
5. 결정한 반복 수로 학습 70% 전체를 다시 학습한다. 최종 test로 모델을 선택하지 않는다.
6. 전체 평균, 노선 평균, 인접 edge·시간대 평균을 학습 구간에서만 만든 비교 기준으로 사용한다.
   이 고정 통계는 새 LightGBM 입력이 아니므로 target encoding 누수가 없다.
7. MAE/RMSE/P95 절대오차/bias/20초 이내 비율, 시간대별·노선별·미등록 ID 오차를 기록한다.
8. 현재 마스터의 인접 순번과 학습 관측 조합을 조인하여 서빙용 `edges.parquet`을 생성한다.

기존 v12도 같은 과거 평가 기간을 본 모델이므로 새 test는 완전히 새로 확보한 미래 평가라고
표현하지 않는다. 기존 v12와 정확히 같은 행 비교, 학습 표본 수별 오차, 실제 카카오 경로의
edge 커버리지·경로 합산 정확도·지연 성능 비교는 후속 실자료 검증 항목이다.

## 모델 묶음

| 파일 | 역할 |
|---|---|
| `model.txt` | LightGBM 공식 텍스트 모델. Python pickle 실행 없이 CPU 추론으로 읽음 |
| `input_spec.json`, `static_bus_features.py` | vocab·고정 공휴일·동일 학습/예측 변환 |
| `feature_schema.json`, `preprocessing.json` | 특성 순서·버전·제외 항목·시간 기준 |
| `edges.parquet` | 현재 공식 인접 조합, 방향, 학습 관측 수·최초/최종 관측일 |
| `fixed_statistics.parquet` | 학습 구간에서 만든 edge/시간 평균 비교 통계. 요청 처리 때 읽지 않음 |
| `serving_manifest.json` | 모델/특성/데이터/마스터 버전, 기술적 지원 날짜, SHADOW 승인 상태 |
| `metrics.json`, `coverage.json`, `source_usage.json` | 실제 평가, 현재 서울 마스터 커버리지, 입력 파일 SHA·품질·분할 |
| `bundle.json` | 필수 산출물 SHA-256. 외부 파일 신뢰 인증이 아니라 묶음 일관성 검사 |
| `test_predictions.parquet` | 평가 정답/예측 재검증용. 서빙 로딩에는 불필요 |

실행 경로는 GPU `j-moo/experiments/runtime/static-bus-20260918`, 결과는
`j-moo/experiments/runs/20260918-v13-static`(GPU 실패 기록), 재실행 결과는
`j-moo/experiments/runs/20260918-v13-static-cpu`다. 각 로그는 같은 runs 아래 `.log` 파일이다.
GPU 0 오류 이후 CPU 최대 16개를 명시적으로 선택한 실행이며 GPU 학습 완료로 기록하지 않는다.
기존 v12 디렉터리나 활성 모델 포인터를 교체하지 않는다.

## HTTP 계약과 적용 규칙

정확한 스키마는 `ml-serving.openapi.json`의 `POST /v2/bus/predictions:batch`를 따른다.
요청 형식은 v1과 같다. 배치는 최대 1,000개, 각 `segment_key`는 고유해야 한다.

```json
{
  "request_id": "example",
  "segments": [{
    "segment_key": "route-1-edge-1",
    "provider_namespace": "SEOUL_BUS",
    "provider_route_id": "100000001",
    "provider_from_stop_id": "100000002",
    "provider_to_stop_id": "100000003",
    "from_stop_sequence": 1,
    "service_date": "2026-09-18",
    "hour_slot": 9
  }]
}
```

위 ID는 형식 예시이며 실제 매핑 성공을 주장하지 않는다. 정상 응답은 배치 버전과
`computed_at`, `valid_until`, `approval`, `production_ready=false`를 포함한다.
개별 `prediction`은 `mean_seconds`, `training_ids_verified=true`, `training_sample_count`,
`observed_from`, `observed_until`, 결측 0을 반환한다.

- `training_sample_count`: 해당 노선·정류장 쌍·순번의 **학습 행 수**다.
- v12 `history_sample_count`: 77~126일 전 같은 시간대의 **조회된 관측 수(최대 8)**다.
  두 필드를 대체하거나 같은 의미로 기록하지 않는다. v2에는 `history_sample_count`가 없다.
- `training_ids_verified=true`는 현재 마스터 인접 조합과 학습 관측 조합의 일치다.
  과거 모든 날짜의 topology, 개별 차량 이동, 운영 정확도 승인까지 검증했다는 뜻이 아니다.
- `SHADOW`는 수치를 반환하지만 Backend 시간 교체를 막는다.
- Python과 Backend 양쪽에서 명시적으로 켠 `TEST_APPLY`만 일정 평가 PREVIEW에 사용한다.
  검색 추천은 기존 운영 승인 경계를 유지하여 별도 preview 수치로만 제공한다.
- `PRODUCTION` 승격은 이번 구현에 없다. v12/v13 모두 임의로 `production_ready=true`로 바꾸지 않는다.
- 날짜/namespace/인접 조합/양수 예측 검증 실패는 해당 edge `UNAVAILABLE`이다.
  Backend는 버스 step의 모든 edge 성공을 확인한 후만 합산하며 하나라도 실패하면 기존 카카오 step 시간을 유지한다.
- 5분 `valid_until`은 응답 재사용 제한이며 실시간 교통 반영 여부가 아니다.

## 파트별 연결 방법

ML 담당:

1. 실제 GPU 로그·완료 상태·학습/평가 분할·성능·커버리지를 확인한다.
2. 필수 산출물 SHA를 확인해 새 디렉터리로 설치한다. 기존 활성 묶음과 분리한다.
3. `ML_STATIC_BUNDLE_DIR`를 새 묶음으로 설정한다. 기본 `ML_STATIC_TEST_APPLY=false`는 SHADOW다.
4. `/v2/bus/health` 및 실제 알려진 edge로 배치 응답을 확인한다.
   기존 `/health/ready`는 v1 준비 상태이므로 인프라 담당자가 v2 시험 인스턴스의 readiness를 따로 결정해야 한다.
5. 요청 중 Parquet/DB/HDFS 조회가 없는지, batch 크기별 latency와 기존 v12 비교를 측정한다.

Backend 담당:

```properties
ddubi.recommendation.ml-preview-api-version=v2
ddubi.recommendation.ml-static-model-version=20260918-v13-static-cpu
ddubi.recommendation.ml-static-master-version=METRO-BUS-20260917-SEOUL-0902
ddubi.recommendation.ml-static-test-apply=false
```

기존 `ml-preview-base-url`은 v2 API를 제공하는 검증 인스턴스를 지정한다. 버전 미설정/불일치는
적용을 거절한다. 시험 적용 시에만 양쪽 flag를 true로 명시한다. 다음 edge의 진입 시간대는
앞 edge 예측에 따라 바뀌므로 Backend의 순차 평가/요청 캐시는 유지한다.
서버 측 과거 파일 조회 제거가 HTTP 호출 횟수까지 제거하는 것은 아니다.

데이터 담당:

- 현재 마스터·학습 인접 조합의 미일치 목록을 검토하고 노선 개편/순번 변경을 구분한다.
- server2 실측/시간평균·주간/월간 자료의 원천 키와 중복 범위를 별도로 증명한다.
- 모델 입력을 늘리려면 학습·서빙 공통 변환과 고정 데이터의 시점 근거를 함께 변경한다.

FE 변경은 없다. 시험 적용 이유·학습 관측 수는 Backend 진단 메타데이터로 구분된다.

## 검증과 남은 작업

- ML 서비스 테스트 57개 통과: 기존 v1 회귀 + v2 인접 조합/날짜/출처 거절, SHA 변조,
  온라인 과거 파일 미조회, 두 모드의 `production_ready=false` 검증 포함.
- 작은 합성 데이터 80행으로 학습 56행/test 24행, 묶음 생성·지표·현재 마스터 조인까지 실행했다.
  이 합성 지표를 실제 교통 정확도로 사용하지 않는다.
- 실제 서버 CPU 학습·평가 및 `20260918-v13-static-cpu.zip` 생성은 완료했다.
  로컬 수신·SHA 검증을 통과했으며 서버 golden 10개와 로컬 예측 차이는 모두 0초다.
- Backend 관련 21개 테스트 통과: HTTP v1/v2, 버전/시험 flag, 전체-step fallback,
  시간대·날짜 경계, 후보 비교 포함. FE·인프라·CI·운영 배포는 수정하지 않았다.

## 2026-09-18 실제 학습 결과

- 학습 69,882,276행, 테스트 30,145,362행, 최종 595 iterations. GPU CUDA 오류 후 같은 서버의 CPU 최대 16개로 완료했다.
- 전체 테스트 MAE 37.378초, RMSE 187.888초, 절대오차 P95 96.595초, 20초 이내 비율 48.452%다.
- 기존 v12와 키·정답이 일치하는 30,004,903행에서 MAE는 v12 약 25.379초, v13 약 37.377초다.
  **과거 조회 의존성 제거는 달성했지만 정확도는 악화되었으므로 운영 승격 근거가 아니다.**
- 현재 서울 버스 마스터 인접 구간 40,959개 중 학습에서 관측된 동일 키는 34,862개(85.114%)다.
  이는 카카오 후보 전체 적용률이나 실제 여정 정확도가 아니다. 과거 노선 구조의 시점별 정합성도 미검증이다.
- 학습 서버 결과 디렉터리에 `metrics.json`, `comparison_v12.json`, `coverage.json`,
  `training_runtime.json`, `golden.json` 및 SHA가 포함된 번들이 있다.
  활성 모델은 교체하지 않았다. 기본 SHADOW 및 `production_ready=false`를 유지한다.

### 로컬 실모델 검증

#### 원천별 0 처리 확인 (2026-09-18)

- 후속 실제 계보 검사: [v13 계보 검증 결과](v13-lineage-audit-20260918.md).
  판정은 **PARTIAL**이며 원본 9개 날짜와 전체 하류 1,476개 날짜는 일치했다.
  원본 전 기간 검사가 완료되기 전 운영 후보 승격을 금지한다. 기존 모델·산출물은 보존한다.

- 현재 `tools/extend_reference_bundle.py` 정규화는 OA-21217에 `> 0`, 나머지 세 원천에
  `>= 0`을 사용한다. OA-21218/21219/21220의 실제 0은 보존된다.
- 네 원천 각각의 0·빈값·음수·양수 처리 회귀 테스트를 추가했고 관련 테스트 7개가 통과했다.
- v13 실제 실행 소스 `training_source.py`는 OA-21217의 `target_seconds > 0`만 학습 정답으로
  채택한다. OA-21218/21219/21220 컬럼은 읽지 않아 세 원천의 0 처리에 따른 직접 영향은 없다.
- 이 확인은 현재 정규화 코드와 v13 입력 처리에 대한 것이다. 재사용한 과거 `model_features`를
  생성한 전체 원천→집계 과정이 검증되었다는 의미는 아니다. 이미 집계에 포함된 0의 영향은
  최종 양수 정답 필터만으로 판별하거나 복구할 수 없다.
- 따라서 이번 규칙만으로 즉시 재학습해야 할 결함은 확인되지 않았다. 기존 SHADOW 상태를 유지한다.
  과거 정규화 오류가 발견되면 원본 보존 자료부터 원천별 정규화 → 관련 집계/특성 생성 →
  같은 날짜 분할로 재학습·재평가 → 번들 재생성 순서로 처리한다. OA-21218/21219/21220만의
  오류라면 해당 입력을 사용하는 v12에 영향이 있고, 이를 사용하지 않는 v13은 직접 재학습 대상이 아니다.

- 설치: `artifacts/ml-serving/20260918-v13-static-cpu`.
- ZIP SHA256: `8ea6055a116c48b3a17b5802036931a2e47696bbfebee5c645b3c251b5611abd`.
- `BusStaticLiveModelTest` 1개 통과(실제 Java → HTTP → Python 모델 호출 10건).
  기존 Backend 관련 21개와 별도로 실행했으며 전체 관련 검증은 22개다.
- 기존 1~20 카카오 예시의 저장된 매핑 결과에서 서울 버스 대안 273개 중 218개(79.85%)는
  모든 edge 예측이 가능했다. 나머지 55개는 전체 버스 step에 카카오 시간을 유지한다.
- 서로 다른 서울 edge 1,008개 중 839개(83.23%)가 지원된다. 경기/인천 대안 450개 및
  매핑 미확정 대안 45개는 이번 서울 모델 평가 대상이 아니다.
- 단일 edge warm 추론 30회: P50 30.27ms, P95 40.76ms. HTTP·이전 도보·대기·지하철을
  포함한 전체 추천 지연시간이 아니며, 기존 매핑을 다시 수행한 결과도 아니다.
- 상세 결과: `data/static-bus-v13/corpus-evaluation.json`.
- 전체 테스트의 고정 edge×hour 평균 baseline MAE는 34.526초로 새 모델보다 작다.
  새 모델은 미관측 ID 오차가 크고 비양수 예측도 있어 서빙에서 해당 값을 거절한다.
  운영 승격 전 정확도 개선과 실제 여정 정답 기반 검증이 필요하다.
