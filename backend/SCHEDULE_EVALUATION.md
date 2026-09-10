# 일정 ML 검증 실행 메모

동작 계약: [schedule-reevaluation.md](../docs/contracts/schedule-reevaluation.md).
모델 계약: [ml-serving-http.md](../docs/contracts/ml-serving-http.md).

기존 MySQL·Redis·카카오 경로 설정과 Flyway V5를 사용한다. 이번 변경은 추가 DB migration이 없다.
검증용 ML은 운영용 BusPredictionPort 승인 조건과 별개이며 출발 변경/알림/운영 recommendation 저장에 사용하지 않는다.

| Spring property | 기본값 | 의미 |
|---|---|---|
| ddubi.recommendation.ml-preview-base-url | 빈 문자열 | Python 서버 주소. 미설정이면 API_ONLY와 ML_NOT_CONFIGURED |
| ddubi.recommendation.ml-preview-timeout-ms | 5000 | HTTP 연결/요청 제한, 1~5000ms |
| ddubi.recommendation.monitor-enabled | true | DB 감시기 실행 |
| ddubi.recommendation.scheduler-delay-ms | 15000 | due 목록 확인 간격(ms) |
| ddubi.recommendation.candidate-ttl-seconds | 900 | 카카오 후보 캐시(초) |
| ddubi.recommendation.kafka-enabled | false | segment.status 소비 활성화 |

이전 search-step-seconds/max-advance-seconds/max-evaluations 설정은 이번 검증 흐름에서 사용하지 않는다.
후보 비교는 같은 출발시각 기준이며 조기 출발은 계산하지 않는다. ML 비교의 요청 64회/20초 한도는
MlRoutePreview에 격리되어 있다. 각 edge 순차 호출이므로 운영 적용 전에 호출량/응답시간 측정이 필요하다.

## 로컬 모델 연결

저장소 루트에서 모델이 준비된 Python 환경으로 실행한다. 모델/참조 파일 설치는 ml-serving/README.md를 따른다.

```powershell
& ./ml-serving/.venv/Scripts/python.exe -m uvicorn app:app --app-dir ml-serving --host 127.0.0.1 --port 8000
```

백엔드를 실행할 PowerShell에 아래 환경변수를 설정한다. 서로 다른 터미널에 설정한 값은 공유되지 않는다.

```powershell
$env:DDUBI_RECOMMENDATION_ML_PREVIEW_BASE_URL = 'http://127.0.0.1:8000'
$env:DDUBI_RECOMMENDATION_ML_PREVIEW_TIMEOUT_MS = '5000'
```

별도 컨테이너에서 실행한다면 localhost를 복사하지 말고 해당 네트워크의 실제 모델 서비스 주소를 지정한다.
/health/ready의 READY_FOR_PREVIEW를 확인한 뒤 일정 등록 또는 POST recommendations를 호출한다.
응답 preview.fastest가 최소시간 후보이며 candidates의 mlSteps가 0이면 모델 시간이 적용되지 않은 것이다.
모델 오류/주소 누락도 API 예상시간으로 결과가 나올 수 있으므로 READY만 보고 ML 성공이라고 판단하지 않는다.

## 테스트

Java 17, Maven, Docker 실행 환경에서 backend 폴더 기준:

```shell
./mvnw -Dtest=MlRoutePreviewTest,BusPreviewClientTest,ScheduleEvaluationIntegrationTest,RouteEvidenceAssemblerTest,RouteTimelineEvaluatorTest,ScheduleMonitoringPolicyTest,RouteSearchServiceTest,BusJourneyEvaluatorTest test
```

실제 로컬 모델을 준비한 뒤 별도 smoke 테스트(기본 테스트 실행에서는 비활성):

```shell
./mvnw -Dtest=BusPreviewLiveModelTest -Dddubi.ml.smoke-url=http://127.0.0.1:8000 test
```

이 테스트는 2026-09-10 golden의 독립 버스 edge 2개와 Java→Python HTTP 수치 일치를 확인한다.
두 표본이 하나의 실제 경로라는 뜻이나 모델 정확도 검증 완료를 의미하지 않는다.
Flutter는 client에서 flutter analyze/test 및 일정 상세의 PREVIEW·fallback 표시를 별도로 확인한다.

## 검증 결과 (2026-09-10)

계산·HTTP 계약·MySQL/Redis 통합 및 기존 경로 회귀 34개 통과. 별도 실제 모델 smoke 1개도 통과했다.
Java에서 로컬 Python v12를 호출한 두 평균값은 145.1247447449537초, 340.31162658466326초로
기존 golden과 일치했다. 첫 호출의 HTTP/2 upgrade 실패를 피하기 위해 HTTP/1.1을 명시했고,
실제 모델 응답시간을 고려해 기본 요청 timeout을 5초로 설정했다. 실패 시 API fallback은 유지한다.
Flutter 빌드 및 실제 사용자 카카오 경로·운영 이벤트 전체 연동은 아직 미검증이다.
