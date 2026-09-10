# 일정 재평가 실행 메모

동작/API 계약: [schedule-reevaluation.md](../docs/contracts/schedule-reevaluation.md).

기존 MySQL·Redis·카카오 경로 설정을 사용한다. 시작 시 Flyway V5가 적용되어야 한다.
기존 데이터의 출발시각은 null로 유지한다. 설정/배포 파일 자체는 이번 변경에서 수정하지 않았다.

| Spring property | 기본값 | 의미 |
|---|---|---|
| `ddubi.recommendation.monitor-enabled` | true | DB 감시기 실행. false이면 수동 평가 API만 사용 |
| `ddubi.recommendation.scheduler-delay-ms` | 15000 | due 목록 조회 간격(ms) |
| `ddubi.recommendation.candidate-ttl-seconds` | 900 | 카카오 후보 캐시(초) |
| `ddubi.recommendation.search-step-seconds` | 60 | 조기 출발 탐색 간격(초) |
| `ddubi.recommendation.max-advance-seconds` | 3600 | 최대 앞당김 탐색 범위(초) |
| `ddubi.recommendation.max-evaluations` | 2000 | 한 실행의 타임라인 평가 상한 |
| `ddubi.recommendation.kafka-enabled` | false | segment.status 소비 활성화 |

Kafka 사용 시 기존 브로커를 `spring.kafka.bootstrap-servers`에 지정한다. 소비 그룹은
`ddubi-schedule-evaluation-v1`이다. 이벤트 수신은 조회 예약이며 실제 수치는 Redis에서 읽는다.

```shell
# Java 17, Maven, Docker가 실행되는 환경에서
./mvnw -Dtest=RouteTimelineEvaluatorTest,ScheduleMonitoringPolicyTest,RouteEvidenceAssemblerTest,RouteSearchServiceTest,BusJourneyEvaluatorTest test
./mvnw -Dtest=ScheduleEvaluationIntegrationTest test
```

Windows는 `mvnw.cmd` 또는 설치된 Maven으로 같은 인자를 실행한다. 통합 테스트는 기존
IntegrationTestSupport의 MySQL 8/Redis 7 Testcontainers를 사용한다. 테스트 프로필에서는
감시기를 꺼 테스트가 제어하는 시각으로 직접 평가한다.

Flutter 환경에서는 `client`에서 `flutter pub get`, `flutter analyze`, `flutter test` 후
등록 → 상세 예측 → 대안 확인/수락 → 실제 출발 확인을 검증한다. 단말에서 표시하는 KST와 서버
절대시각이 맞는지도 확인한다. 화면 경로는 기존 일정 상세이며 홈의 별도 데모 화면 전체를 바꾸지는 않았다.

검증 상태: 계산/경로 회귀 20개 통과. 로컬 Docker 엔진 미연결로 DB 통합 5개 미검증,
Flutter SDK 부재로 Flutter 빌드 미검증. 실제 서비스 구동 완료를 의미하지 않는다.
