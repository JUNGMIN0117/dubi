# 일정 시작시각 기반 계산 — 프로젝트 적용 계획

- 기록일: 2026-09-16.
- 근거: 최종 계산 로직 설명과 프로젝트 전체 구조를 확인한 파일별 수정·추가·삭제·대체 계획.
- 상태: **구현 전 작업 범위 기록. 이 문서를 추가하는 작업에서는 실행 코드·스키마·설정·데이터·배포를 변경하지 않는다.**
- 조사 기준: `feature/dt/S15P21E202-89-segment-baseline`, HEAD `b994af1`과 당시 미커밋 파일. 이 브랜치·이슈가 아래 전체 구현의 담당 작업으로 확정됐다는 뜻은 아니다.
- 목표 로직: [일정 시작시각 기반 경로 계산](arrival-deadline-calculation.md).
- 조사 범위: 전체 디렉터리 구조와 일정 등록·경로 평가·수집·서빙·알림·프론트의 관련 실행 경로. 모든 정적 데이터의 정확성이나 운영 배포 상태를 검증한 것은 아니다.
- 표의 `수정/추가/삭제/대체`는 앞으로의 작업이다. 신규 구성요소 이름과 정확한 DB 필드는 구현 시 계약으로 확정한다.

## 1. 변경의 중심

기존 경로·매핑·저장·스케줄러를 재사용하면서, 계산의 중심을 사용자 입력 출발시각에서 일정 시작시각까지 도착 가능한 출발시각 탐색으로 전환한다.

```text
현재 실행 흐름
일정 저장 → DB 스케줄러가 평가 대상 발견 → ScheduleEvaluationWorkflow
→ 외부 후보 + RouteEvidenceAssembler → MlRoutePreview
→ RouteTimelineEvaluator → ScheduleEvaluationStore

목표 실행 흐름
일정 저장 + 평가 작업 등록 → 즉시 실행
→ 후보 확보 → 계산 증거 고정 → 최신 출발시각 탐색
→ 탑승·환승·이용 가능성 검증 → 추천 선택
→ 변경 원인·알림 판단 → 결과·발송 대기 저장
→ 정기/사건/사용자 상태 변경 시 같은 계산 재실행
```

현재 Store에는 입력 버전·lease·계산 중 사건 변경 처리 기반이 있다. 이를 버리지 않는다. Backend 변경만으로 완료되지 않으며, 프론트의 단순 역산과 수집 주기까지 함께 변경해야 한다.

## 2. 프로젝트 전체 영역

| 경로 | 현재 역할 | 적용 방향 |
|---|---|---|
| [client](../../client) | Flutter 화면·API 호출 | 일정 시작 입력, 서버 추천·원인·품질 표시, 실제 이동 상태 전달 |
| [backend](../../backend) | 일정·경로·추천·알림·사용자 관리 | 핵심 변경: 공통 여정 계산·출발 탐색·재계산·알림 판단 |
| [collector](../../collector) | 외부 교통·날씨·공지 수집 | 주기·대상·호출 예산 조정, 필요한 원천 필드 보존 |
| [sparkjobs](../../sparkjobs) | 최신값·구간 상태·기준선 | 운행 이상·사건 수명·영향 구간·변경 이벤트 연결 |
| [ml-serving](../../ml-serving) | 버스 구간 예측 HTTP | 계산 재사용, 운영 적용·품질·출력 계약 보완 |
| [archiver](../../archiver) | 원본 보존·Replay | 기반 유지, 새 사건·원본 토픽·재현 시나리오 연결 |
| [infra](../../infra) | 배포·Kafka·Spark·Redis 등 | 필요한 잡·설정·모니터링 요구사항을 인프라 담당자에게 전달 |
| [notification](../../notification) | 별도 서비스용 자리 | 당장 독립 서비스를 만들지 않고 Backend FCM 기반 사용 |
| [docs/contracts](.) | 컴포넌트 계약 | 입력·출력·시간 계산·사건 의미를 구현과 함께 갱신 |

데이터 담당의 주 변경 범위는 Collector·Spark·계약이다. Backend 일정·추천, Client, 인프라는 각 담당 범위와 인터페이스를 맞춰 진행한다. 이 계획은 전체 영향 범위를 나타내며 모든 파트의 파일을 즉시 수정하라는 지시가 아니다.

## 3. 일정 입력과 프론트

| 파일 | 작업 | 수정 지점 |
|---|---|---|
| [screens.dart](../../client/lib/presentation/screens.dart) | 수정 | `ScheduleFormScreen`의 출발 날짜·시간 입력과 `_save()` 출발 필수 로직 제거. 일정 시작시각 입력과 등록 직후 계산 상태 표시 |
| [models.dart](../../client/lib/domain/models.dart) | 수정 | 사용자 입력, 서버 권장 출발, 사용자 선택 계획, 실제 출발을 구분. 추천 결과를 사용자 입력처럼 재전송하지 않기 |
| [schedule_api.dart](../../client/lib/services/schedule_api.dart) | 수정 | 일정 생성·수정과 최신 평가 응답 계약 반영 |
| [api_schedule_repository.dart](../../client/lib/services/api_schedule_repository.dart) | 수정 | 변경된 평가·수락·출발 상태 인터페이스 연결 |
| [schedule_evaluation.dart](../../client/lib/domain/schedule_evaluation.dart) | 확장 | 현재 Repository 인터페이스와 함께 명시적인 평가 결과 타입 도입. 상태·필드의 Map 해석 분산 방지 |
| [schedule_evaluation_card.dart](../../client/lib/presentation/schedule_evaluation_card.dart) | 대체·수정 | 입력 출발 중심 표시를 권장 출발·도착·지각·원인·최신성으로 변경. `DEPARTURE_REQUIRED` 제거. 수락과 실제 출발 구분 |
| [recommendation_api.dart](../../client/lib/services/recommendation_api.dart) | 계산 대체 | `desiredArrivalAt - durationMinutes`로 만든 출발과 목표시각을 그대로 쓴 도착 표시 제거. 서버 평가 사용 |
| [notification_center.dart](../../client/lib/presentation/notification_center.dart) | 수정 | 알림에서 해당 일정·평가·변경 근거로 연결 |

현재 일반 경로 검색은 API 총시간을 목표 도착시각에서 빼며, 후보 순서로 추천·최단·안전 종류를 부여한다. 이를 서버의 검증된 결과·순위로 대체해야 일정 상세와 검색 화면이 다른 계산을 보여주지 않는다. 일정 저장 전 일반 검색에도 도착 목표 계산이 필요하면 같은 공통 계산기를 쓰는 조회 계약을 마련한다.

화면의 결과 조회 주기와 서버의 재계산 주기는 다르다. 앱을 켜 두어야만 재계산되는 구조로 만들지 않는다. 신규 결과 타입·상태가 도입되면 실제 API와 Mock의 역할을 구분하고, Mock의 ETA를 서버 계산처럼 표시하지 않는다.

## 4. Backend 일정 등록·수정·반복 일정

| 파일 | 작업 내용 |
|---|---|
| [ScheduleCreateRequest.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/dto/request/ScheduleCreateRequest.java), [ScheduleUpdateRequest.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/dto/request/ScheduleUpdateRequest.java) | `arriveBy`를 일정 시작에 해당하는 도착 목표로 사용. `plannedDepartAt` 입력 계약은 호환성을 고려해 단계적으로 폐기 |
| [ScheduleService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/service/ScheduleService.java) | `create/update`에서 일정과 평가 작업을 함께 등록. 커밋 뒤 실행을 깨우고 DB 스케줄러는 누락·재시작 복구 수단으로 유지 |
| [ScheduleRepeatService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/service/ScheduleRepeatService.java) | `materialize`의 반복 일정 생성·재생성에도 최초 평가 등록. 취소·개별 수정·재생성 시 오래된 작업 무효화 |
| [Schedule.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/entity/Schedule.java) | 사용자 입력 출발, 권장 출발, 채택 계획, 실제 출발의 소유권·저장 위치 정리 |
| [ScheduleResponse.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/dto/response/ScheduleResponse.java), [HomeService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/service/HomeService.java) | 상세·목록·홈에서 같은 최신 추천 요약과 상태를 사용하도록 연결 |

현재 반복 일정 생성은 출발 입력을 만들지 않고, 평가 대상 SQL은 출발 입력 없는 일정을 제외한다. 단건 등록만 고치면 반복 일정이 남으므로 함께 전환한다. 일정 저장 트랜잭션 안에서 외부 API를 직접 기다리지 않는다.

## 5. 공통 계산기와 출발시각 탐색

| 파일 | 현재 상태 | 적용 작업 |
|---|---|---|
| [ScheduleEvaluationWorkflow.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/ScheduleEvaluationWorkflow.java) | 입력 출발에서 후보 비교 | 후보 확보 → 증거 고정 → 출발 탐색 → 추천 선택 → 변경 판단 → 저장으로 흐름 대체 |
| [DepartureSuggestionService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/DepartureSuggestionService.java) | 입력 출발을 앞당기는 격자 탐색, 현재 Workflow에 미연결 | 도착 목표·현재시각·탐색 범위로 최신 출발을 찾도록 대체. 시간표 후보와 제한된 시간 격자 지원 |
| [MlRoutePreview.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/MlRoutePreview.java) | 실제 버스·대기·지하철 계산과 검증용 비교가 함께 있음 | 실제 계산을 공통 서비스로 추출. 검증용 비교는 별도로 유지하거나 전환 완료 후 정리 |
| [RouteTimelineEvaluator.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/RouteTimelineEvaluator.java) | 순차 계산·탑승 검사·통제 겹침 판단 | 재사용·확장. 선택 차량과 탑승 구간 연결, 바뀐 진입시각의 후속 구간 계산, 불확실성 전달 |
| [JourneyEvidence.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/JourneyEvidence.java) | 이동·대기·통제 증거 모델 | 운행별 정차시각, 적용기간, 사건 근거, 품질·예측 범위 표현 확장 |
| [RouteEvidenceAssembler.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/RouteEvidenceAssembler.java) | 구간 상태·도로 증거, API 요소 분해 | 시간표·운행 이상·기준선을 같은 평가 입력으로 고정. 결측·충돌·fallback 정책 연결 |

[평가 패키지](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation)에 추가하거나 기존 클래스에서 분리할 책임:

| 구성요소 | 책임 |
|---|---|
| 공통 여정 계산 서비스 | 경로·출발·고정 증거로 탑승·환승·도착 계산 |
| 추천 선택 정책 | 이용 가능성·품질·최신 출발·환승·도보·조기 도착 비교 |
| 예측 품질·대체값 정책 | 실시간·시간표·기준선·API fallback 및 중요도별 보수 정책 |
| 결과 차이 분석기 | 이전·현재 경로, 출발·도착, 사건, 품질 변화 추출 |

새 파일명은 제안 단계다. 책임을 모두 하나의 Workflow에 넣거나 비슷한 계산 엔진을 여러 개 새로 만들지 않는다.

탐색 전체에서 원본·모델·정책 버전과 입력별 캐시를 공유하되 출발 변화에 따른 구간 진입시각·차량·예측은 다시 계산한다. 기존 한 출발의 고정 RIDE·WAIT 결과를 그대로 `DepartureSuggestionService`에 넘기는 것만으로 완료되지 않는다.

## 6. 교통수단별 계산과 마스터

| 파일·경로 | 적용 작업 |
|---|---|
| [BusWaitingService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/BusWaitingService.java) | 실시간 대기와 먼 미래 계획 대기를 구분. 기존 `0.6 × 배차간격` 정책을 실제 차량 시각과 혼합하지 않기. 배차간격 범위의 대표값 등 미확정 세부 정책은 별도 확정 |
| [BusPreviewClient.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/BusPreviewClient.java) | 공통 예측 경계로 정리. 탐색 전체 입력별 캐시·호출 한도·모델 버전 고정 |
| [SubwayTimingService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/SubwayTimingService.java) | 후속 역 예측·취소·무정차·중단을 반영. 알려진 장애를 평시 시간표 fallback으로 덮지 않기 |
| [SubwayTimetable.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/SubwayTimetable.java) | 운행일·자정 검증 재사용. 역·방향·시각별 인덱스와 도착 기준 운행 후보 조회 확장 |
| [시간표 도구](../../backend/tools/transit-timing) | 원본·운행일 변환·실시간 열차 대응·런타임 파일 형식 통일. 독립 분석 인덱스와 현재 로더의 형식 차이 해결 |
| [RoadImpactEvidence.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/RoadImpactEvidence.java) | 현재 영향 가능성에서 확인된 완전 통제·부분 통제·해제·확인 불가를 구분하도록 확장 |
| [도로 대응 도구](../../backend/tools/road-impact), [도로 마스터](../../backend/transit-master/road) | 방향·도로 ID namespace·실제 통과 구간이 검증된 대응표 확보·검증 |

일반 도보와 환승은 현재 결정대로 제공자의 해당 WALK 시간을 유지한다. 미검증 환승 보정·횡단보도 여유를 이번 기능에 자동 가산하지 않는다. API의 대기·접근·환승 시간 분해를 검증한 뒤 분리 합산을 적용한다.

다음 기반은 유지한다.

- [MasterDataTransitMappingResolver.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/service/MasterDataTransitMappingResolver.java)의 보수적 매핑.
- [CanonicalSegmentIdFactory.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/mapping/CanonicalSegmentIdFactory.java)의 공통 ID 체계.
- [RouteCandidateAssembler.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/RouteCandidateAssembler.java)와 [RouteSegmentEdgeWriter.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/service/RouteSegmentEdgeWriter.java)의 저장·edge 연결.
- 외부 후보 생성 경계인 [PublicTransitRouteClient.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/client/PublicTransitRouteClient.java).

일반 검색의 [RouteSearchService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/service/RouteSearchService.java)와 [BusJourneyEvaluator.java](../../backend/src/main/java/com/ssafy/ddubi/domain/route/prediction/BusJourneyEvaluator.java)는 실제 검색 API에 연결돼 있다. 이름이 비슷하다는 이유로 미사용 코드로 삭제하지 않고, 검색에서도 최종 평가가 필요할 때 공통 계산 결과를 사용하도록 정리한다.

## 7. 재계산·상태·DB

| 파일 | 수정 지점 |
|---|---|
| [ScheduleEvaluationStore.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/ScheduleEvaluationStore.java) | `claim` 출발 필수 검사, `latest`의 `DEPARTURE_REQUIRED`, `due`의 `planned_depart_at IS NOT NULL` 대체 |
| 같은 Store의 `complete/accept` | 권장시각과 사용자 선택 구분. 정상·지각·정보 부족·탐색 미완료 저장. 추천 수락을 일정 시작 입력 변경과 혼동하지 않기 |
| [ScheduleEvaluationScheduler.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/ScheduleEvaluationScheduler.java) | 최초 즉시 실행 연결, 처리량·우선순위·제한된 재시도 보완. 현재 20건 순차 처리의 장시간 외부 호출 영향 관리 |
| [ScheduleMonitoringPolicy.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/ScheduleMonitoringPolicy.java) | 일정 기준과 권장 출발 기준을 명시적으로 정책화. 장거리 일정·늦은 등록·권장 출발 변경 처리 |
| [SegmentStatusListener.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/adapter/SegmentStatusListener.java) | 사건 ID·버전 중복 처리와 지하철 운행 이상·해제 이벤트 연결 |
| [ScheduleEvaluationWakeup.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/ScheduleEvaluationWakeup.java) | 같은 일정에 몰리는 사건 묶기. 선택 경로 외 후보 회복·변경을 감시할 범위도 명시 |

입력 버전 검사, lease, 계산 중 사건 발생 시 다음 확인을 앞당기는 기존 방어는 유지한다. 최초 계산 미완료 시 아직 저장 경로가 없어 사건 역조회가 어려운 구간도 고려한다. 사용자의 선택 경로와 최신 추천 경로가 다르면 어떤 구간을 감시하는지 명시해야 한다.

[DB migration](../../backend/src/main/resources/db/migration)에 다음 버전 마이그레이션을 추가한다. 이미 적용된 V5 등 과거 파일을 수정하지 않는다.

필요한 정보:

- 권장 출발과 채택 계획의 구분, 실제 출발·이동·도착 상태.
- 계산 근거·버전·유효기간·탐색 완료 여부.
- 처리한 사건 ID·버전 또는 멱등 처리 기록.
- 발송 대기·시도 횟수·다음 재시도 시각·전송 결과.

`recommendation`과 `schedule_evaluation`의 기존 필드·JSON을 우선 재사용하고 조회·동시성 요구에 따라 필요한 스키마만 확장한다. `planned_depart_at`은 기존 앱·데이터 전환 후 제거 여부를 결정한다. 일정 상태 enum을 무조건 확대하기보다 기존 일정 생명주기와 별도 이동 상태의 경계를 먼저 정한다.

## 8. Collector·Spark와 데이터 선행 작업

### 8.1 수집 주기

[kafka_adapter.py](../../collector/src/sources/kafka_adapter.py)의 현재 코드 기본값:

| 데이터 | 기본 주기 |
|---|---:|
| 버스 도착·위치 | 8시간 |
| 지하철 도착 | 6분 |
| 지하철 위치 | 1시간 |
| 지하철 알림·도로 돌발 | 15분 |

기본값과 설정값 중 큰 값을 쓰므로 환경변수를 60초로 낮추는 것만으로 바뀌지 않는다. 실제 운영 호출값·한도·성공률을 측정하고 대상과 주기를 함께 정해야 한다. API 한도를 무시하고 모든 노선을 매분 호출하는 방안은 아니다.

| 파일 | 적용 작업 |
|---|---|
| [kafka_adapter.py](../../collector/src/sources/kafka_adapter.py), [main.py](../../collector/src/main.py) | 주기 하한·실행 주기 정책 변경 |
| [request_budget.py](../../collector/src/request_budget.py), [config.py](../../collector/src/config.py) | 실제 공유 키 예산을 보존하면서 대상·빈도 관리 |
| [sources](../../collector/src/sources) | 기존 수집기 재사용. 원천이 제공하는 사건 상태·방향·범위·갱신 근거 정규화 |

동적 수집 대상 관리가 필요하면 관련 노선·역을 사용자별 중복 없이 합치는 공통 대상 관리 기능을 추가한다. 이 기능을 채택할지와 대상 전달 계약은 호출 예산·범위 조사 후 결정한다.

### 8.2 구간 상태·사건 처리

| 파일 | 적용 작업 |
|---|---|
| [realtime_latest.py](../../sparkjobs/streaming/realtime_latest.py) | 수집 정책과 신선도·보관기간 정합성 조정. 수집 실패와 사건 종료 구분 |
| [road_incidents.py](../../sparkjobs/streaming/road_incidents.py), [road_impact.py](../../sparkjobs/common/road_impact.py) | 수정·해제·만료·중복·충돌 처리 확장. 통제 확정과 영향 가능성을 분리 |
| [segment_status.py](../../sparkjobs/streaming/segment_status.py), [segment_status_live.py](../../sparkjobs/streaming/segment_status_live.py) | 검증된 중단·지연 증거를 상시 계산에 연결. 상태 변경 이벤트 발행 |
| [subway_crosswalk.py](../../sparkjobs/common/subway_crosswalk.py) | 정적 역·노선 매핑 재사용. 운행일별 실제 열차 대응은 별도 검증·연결 |
| [segment_observations.py](../../sparkjobs/streaming/segment_observations.py), [segment_facts.py](../../sparkjobs/common/segment_facts.py), [segment_baseline.py](../../sparkjobs/batch/segment_baseline.py) | 실제 구간 통과 입력 확보·정규화·품질 검증 연결. 기준선 계산 재사용 |

[common](../../sparkjobs/common)·[streaming](../../sparkjobs/streaming)에 추가할 책임은 지하철 공지·운행 변화의 영향 구간 변환, 사건 지속·해제 상태 관리, 동일 운행의 예측 변경과 실측 지연 구분이다. 제공자 간 정교한 사건 통합은 기본 동일 ID 멱등 처리 후 확장한다.

도로 방향 대응표, 시간표와 실시간 열차 대응, 실제 통과 기록 확보는 코드 외의 선행 작업이다. 위치 스냅샷이나 ETA를 실측 통과시간으로 바꿔 기준선에 넣지 않는다. 열차의 일부 역 예측만 있는 경우 나머지 역의 정확한 시각을 확보한 것처럼 표시하지 않는다.

### 8.3 실측 스키마 위치 정정

이전 대화 답변에서 `docs/contracts/events`에 없다는 이유로 실측 구간 스키마 신규 추가를 제안했으나, **[segment-observation.schema.json](segment-observation.schema.json)은 `docs/contracts` 바로 아래에 이미 있다.**

따라서 작업은 같은 스키마를 중복 추가하는 것이 아니라 기존 논리 스키마와 Producer·Spark 입력의 일치, 실제 원천 연결, 필요한 경우 토픽·원본 보존 계약을 검증하는 것이다.

## 9. 원인 안내와 알림

현재 Workflow는 `notificationJson/notificationKey`를 null로 저장해 자동 알림을 생성하지 않는다. FCM 설정만으로 재계산 알림이 발생하지 않는다.

| 파일·경로 | 적용 작업 |
|---|---|
| [추천 서비스](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service) | 변경 원인·알림 판단 구성요소 추가. 사건 근거, 결과 변화, 임계값·해제·중복 정책 처리 |
| [EvaluationNotificationDispatcher.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/EvaluationNotificationDispatcher.java) | 발송 대기 처리, 오래된 평가 배제, 전송 결과에 따른 재시도 연결 |
| [NotificationService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/notification/service/NotificationService.java) | 알림 기록과 외부 전송을 분리하고 실패 건 재시도 경로 추가 |
| [NotificationCommand.java](../../backend/src/main/java/com/ssafy/ddubi/domain/notification/service/NotificationCommand.java) | 필요 시 평가 ID·사건 근거·변경 정보를 전달하도록 확장 |
| [SuppressionStore.java](../../backend/src/main/java/com/ssafy/ddubi/domain/notification/service/SuppressionStore.java) | 동일 사건 중복 억제와 악화·해제 같은 새로운 알림을 구분하는 키 정책 |
| [NotificationType.java](../../backend/src/main/java/com/ssafy/ddubi/domain/notification/entity/NotificationType.java) | 기존 유형 재사용 우선. 통제 사실·해제 등 새 유형이 필요하면 DB enum·앱 계약까지 함께 변경 |
| [FcmPushSender.java](../../backend/src/main/java/com/ssafy/ddubi/global/push/FcmPushSender.java) | FCM 어댑터 재사용. 발송 시도·실패 유형을 작업 상태에 연결 |

현재 실제 코드는 `NotificationService.create()` 안에서 FCM을 동기 호출한다. FCM 실패가 예외 대신 실패 결과로 돌아오면 Dispatcher는 처리 키를 기록한다. README의 비동기·재시도 설명만으로 구현 완료라고 판단하지 않고 위 실행 경로를 보완한다.

공식 통제 사실 알림과 미검증 예측으로 출발을 바꾸는 권고의 승인 조건을 분리한다. PREVIEW 차단만 삭제하여 운영 추천으로 바꾸지 않는다.

## 10. 이동 중 재탐색·불확실성·전체 예외 처리

| 요구사항 | 적용 위치·작업 |
|---|---|
| 실제 출발 | [RecommendationController.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/controller/RecommendationController.java)의 `/departure`가 현재 감시 중단만 하는 부분을 출발시각·이동 상태 기록으로 확장 |
| 이동 중·경로 이탈 | [일정 도메인](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule)에 이동 상태 서비스·입력 DTO 추가. 현재 위치·탑승 상태에서 남은 여정을 공통 계산기로 평가 |
| 위치 수집 | [클라이언트 서비스](../../client/lib/services)에 위치 상태 전달 기능 추가. 동의·관측시각·정확도·거절·중단 처리 |
| 도착 종료 | [ArrivalService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/report/service/ArrivalService.java)에 이동 상태 종료와 마지막 계획·실제 결과 연결 |
| 중요도·기준선 | [BaselineSnapshot.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/BaselineSnapshot.java)에 P80/P95·표본·날씨·버전 정보 확장, 공통 계산기에 연결 |
| ML 운영·품질 | [schemas.py](../../ml-serving/schemas.py), [predictor.py](../../ml-serving/predictor.py)의 검증용 응답·운영 가능 판정을 검증 결과에 맞게 개편 |
| 자정·막차·무정차·폐쇄 | 시간표 서비스와 운행 이상 처리에서 판단해 전체 계산에 전달 |
| 조기 도착 | 추천 선택 정책에 허용 범위·선호 적용 |
| 사건 만료·중복·출처 충돌 | Spark 사건 관리와 Backend 증거 선택에 같은 의미로 적용 |

현재 ML은 구간 평균 예측이며 응답 모델의 `production_ready`도 검증용으로 제한되어 있다. 이를 이름만 P80/P95·성공확률로 바꾸지 않는다. 실측 기반 검증 또는 별도 예측 범위 모델이 필요하다. 기준선 표본 부족도 숨기지 않는다.

[ScheduleStatus.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/entity/ScheduleStatus.java)는 현재 UPCOMING/DONE/CANCELED다. 별도 이동 상태를 둘지 일정 상태를 확장할지는 홈·목록·도착 기록·감시 SQL까지 비교해 결정한다.

[ScheduleConflictService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/schedule/service/ScheduleConflictService.java)의 연속 일정 충돌 계산에는 직선거리·고정 비용 추정이 남아 있다. 이번 출발 계산과 같은 정확도의 결과로 표시하지 않는다. 연속 일정까지 통합할 경우 일정 종료·이동 가능 시작시각 입력을 먼저 정의하고 공통 계산기를 재사용하는 별도 작업으로 둔다.

## 11. 삭제·대체·유지

| 대상 | 처리 |
|---|---|
| 프론트 출발 필수 입력·단순 역산 | 새 계약·화면 전환 후 제거 |
| `DEPARTURE_REQUIRED`, 평가 SQL의 출발 필수 조건 | 도착 목표 기반 처리로 대체 |
| [DepartureSolver.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/DepartureSolver.java) | 기존 반복 역산 호출을 새 탐색으로 옮긴 뒤 삭제 후보 |
| [TravelTimeEstimator.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/TravelTimeEstimator.java) | 기준선·fallback 등 재사용 부분을 옮긴 뒤 중복 계산 정리 |
| [RecommendationService.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/RecommendationService.java) | 기존 `recommend()` 경로 정리. 현재 사용 중인 근거 조회는 유지하거나 별도 조회 서비스로 이전 |
| [RecommendationReasonWriter.java](../../backend/src/main/java/com/ssafy/ddubi/domain/recommendation/service/RecommendationReasonWriter.java) | 새 근거 모델로 통합. 과거 저장 결과 조회 호환성 유지 |
| `backend/src/main/java/com/ssafy/ddubi/domain/recommendation/evaluation/TransferTimingService.java` | 당시 미커밋·미연결 로컬 파일. 이 문서 변경에는 포함하지 않으며 저장소 링크로 제공하지 않는다. 최신 환승 정책과 비교할 대상이며 자동 삭제·채택하지 않음 |
| 마스터·구간 ID·경로 저장·원본 보존 | 유지·재사용 |
| 기존 DB migration·과거 추천 기록 | 유지. 새 migration과 호환 처리로 전환 |

미커밋 환승 코드·테스트·마스터·기존 수집 전달 문서는 다른 작업으로 구분한다. 새 계산기로 호출을 옮기고 참조·테스트·과거 데이터 호환을 확인한 후 삭제한다. 유사한 이름이나 현재 주 API에서 호출하지 않는다는 사실만으로 파일 전체를 삭제하지 않는다.

## 12. 계약·Archiver·인프라

| 계약 | 갱신 내용 |
|---|---|
| [schedule-reevaluation.md](schedule-reevaluation.md) | 도착 목표 입력, 최초 실행, 주기·이벤트·이동 상태 |
| [travel-time-calculation.md](travel-time-calculation.md) | 사용자 출발 입력 전제에서 출발 탐색 구조로 변경 |
| [transit-waiting.md](transit-waiting.md), [subway-timing.md](subway-timing.md) | 계획·실시간 대기, 운행별 시각, 환승 재계산 |
| [road-impact.md](road-impact.md) | 영향 가능·확정 통제·해제·미확인 의미 |
| [redis-keys.yaml](redis-keys.yaml), [topics.yaml](topics.yaml), [events](events) | 사건 변경·수명·최신성·중복 계약. 현재 road-impact의 POTENTIAL_IMPACT 상수와 하위 호환 고려 |
| [segment-observation.schema.json](segment-observation.schema.json) | 기존 실측 논리 스키마와 실제 입력 연결 검증 |
| [recommendation-data-provenance.md](recommendation-data-provenance.md), [ml-serving-http.md](ml-serving-http.md) | 계산 출처·품질·운영 적용 조건·근거 |
| [frontend-api-contract.md](../frontend-api-contract.md) | 입력·추천·지각·정보 부족·이동 상태 응답 |

[Archiver config](../../archiver/src/config.py)는 현재 기본으로 transport.raw/city.raw를 구독한다. 새로운 원본·정규화 토픽을 도입하면 보존 대상과 설정을 함께 검토하되, 기존 보존 구현을 불필요하게 재작성하지 않는다. [replay.py](../../archiver/src/replay.py)로 재생할 사건·계산 버전·시각 정책을 고정한다.

인프라 담당자 전달 항목은 새 Spark 잡과 checkpoint, 사건/실측 토픽·보존, 동일 마스터 artifact와 SHA, 수집 예산, Backend 작업 처리량, FCM 재시도, Kafka lag·데이터 나이·평가 지연·오류율이다. [infra](../../infra), Docker, CI 설정은 데이터 작업자가 임의 수정하지 않는다.

## 13. 구현 순서와 완료 기준

| 순서 | 작업 묶음 | 완료 기준 |
|---|---|---|
| 1 | 입력·결과·정책 계약 | 출발 입력 없는 일정, 감시 기준시각, 권장·선택·실제 출발 의미 확정 |
| 2 | 공통 여정 계산·출발 탐색 | 고정 fixture로 탑승·환승·최신 출발·지각·정보 부족·예산 소진 검증 |
| 3 | 일정·DB 작업·프론트 | 등록 직후 결과, 수정/취소 무효화, 반복 일정, 화면 간 계산 일치 |
| 4 | 수집·운행 이상·통제 | 관련 사건이 해당 일정 재평가로 연결, 최신성·해제·중복 검증 |
| 5 | 원인·알림·재시도 | 근거 있는 이유 전달, 중복 억제, FCM 실패 복구 |
| 6 | 이동 중·막차·품질 정책 | 실제 상태 기반 남은 여정, 운행일·막차·불확실성 처리 |
| 7 | 기존 경로 정리·운영 연결 | 중복 구현 제거, 과거 결과 호환, Replay·처리량 검증 |

수집 예산과 열차·도로 대응표 확보는 1~2단계부터 함께 진행한다. 정책 결정·원천 데이터·코드 구현·운영 검증을 같은 완료 상태로 묶지 않는다.

확장할 테스트 위치:

- [Backend 평가 테스트](../../backend/src/test/java/com/ssafy/ddubi/domain/recommendation/evaluation): 환승 실패, 통제 시간·방향, 최신 출발·전 후보 지각·탐색 미완료, 자정·막차, 동일 입력 재현.
- [Backend 일정 테스트](../../backend/src/test/java/com/ssafy/ddubi/domain/schedule/service): 출발 입력 없는 등록, 반복 일정, 계산 중 수정·취소·출발.
- [Backend 추천 테스트](../../backend/src/test/java/com/ssafy/ddubi/domain/recommendation/service): 저장·버전·시간대·과거 근거 호환.
- [Collector 테스트](../../collector/tests): 공유 키 예산·수집 주기·결측·원천 ID·재시도.
- [Spark 테스트](../../sparkjobs/tests): 사건 중복·충돌·만료·실제 해제, 실측 품질·기준선·늦은 이벤트·Replay.
- [ML 테스트](../../ml-serving/tests): 응답·지원 범위·모델 버전·운영 판정·품질 계약.
- [Client 테스트](../../client/test): 출발 입력 제거, 서버 결과 표시, 원인 불명·지각·정보 부족, 이동 상태·알림 연결.
- 알림 테스트는 Backend에 발송 실패·재시작·새 결과로 인한 이전 알림 취소·동일 사건 재전송 시나리오를 추가한다.

분석 당시에는 위 테스트를 새로 실행하거나 운영 데이터·배포 상태를 검증하지 않았다. 이번 문서 기록의 검증은 파일 경로·상호 링크·내용 구분이며, 실제 구현 시 각 변경 범위의 단위·계약·통합·Replay 검증이 필요하다.
