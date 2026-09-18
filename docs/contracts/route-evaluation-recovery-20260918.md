# 2026-09-18 경로 평가 복구 계약

## 적용 범위

Backend·ML 테스트·도보 설정 수정이며 Flutter 구현은 별도 `route-evaluation-fe-handoff-20260918.md`로 전달한다.
v12 기본 SHADOW 및 실제 적용 모드의 의미는 유지한다.
v13은 **실험 완료 / SHADOW 후보 / 운영 미적용**이다. 이번 작업에서 재학습·활성화·서버 배포를 하지 않는다.
기존 v13 평가에서 v12·기준선보다 낮았던 성능과 미완료 계보 검증을 변경하지 않는다.

## 1. 구간 계산 실패 시 복구

`JourneyEvaluationService`는 BUS 인접 edge 중 하나라도 실패하면 해당 BUS step 전체를 카카오 원본 시간으로 사용한다.
ML RIDE를 받았어도 필수 대기를 계산하지 못하면 RIDE 적용을 취소하고 provider step으로 돌아간다.
부분 ML edge + 전체 provider step, 또는 provider step + 추가 대기를 중복 합산하지 않는다.
최종 시간에서 사용하지 않은 ML 예측은 applied=false로 남긴다.

지하철 시간표 미설정·매핑 결측·유효기간 밖 등의 경우 원본 SUBWAY step을 유지한다.
실제 조회한 열차가 모두 탑승 불가(`SUBWAY_NO_BOARDABLE_TRAIN`)인 경우는 막차 이후 가상 운행을 만들지 않도록 차단한다.
확정 통제는 fallback보다 우선한다. 전체 provider-total fallback에서도 canonical segment ID를 유지하여 통제를 우회하지 않는다.

이 복구는 원본 API 예상 사용이며 실시간 정상 운행 보장이나 ML 예측 성공을 뜻하지 않는다.

## 2. 도보 보강

입력은 사용자 출발지/목적지 좌표와 카카오 대중교통 step의 path.points다.
좌표 순서는 경도(x), 위도(y)다. 출발→첫 승차 끝점, 마지막 하차 끝점→목적지 방향으로 조회한다.
공식 API는 `GET https://dapi.kakao.com/v2/routing/walk`이며 기존 REST API 키를 사용한다.
응답 `route.properties.totalTime`의 초 값을 검증하여 사용한다.
[카카오 공식 REST API 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api#walking).

| 입력 조건 | 처리 |
|---|---|
| 기존 ACCESS/EGRESS WALK가 있고 비용 합 일치 | WALK 시간을 도보 API 값으로 교체하고 총시간에 차이만 반영 |
| 양 끝 WALK가 없고 provider 총시간=step 합 | 누락 WALK를 평가용으로 생성하고 성공한 도보시간을 총시간에 추가 |
| totalTime-step 합의 잔여시간 존재 또는 step 합 초과 | 잔여시간에 이미 도보·대기가 포함됐는지 입증할 수 없어 원본 비용 보존, WALK_COST_COVERAGE_UNRESOLVED |
| API 거부·시간초과·한도·좌표 없음 | 원본 step/총시간 유지, 실패 사유 기록. 생성 도보를 0초 성공으로 저장하지 않음 |
| 환승 WALK | 카카오 대중교통 원본 그대로 사용 |

카카오 경로 끝점 모드의 증거는 source=KAKAO_PUBLIC_TRANSIT, coordinateType=PROVIDER_PATH_ENDPOINT,
judgment=PROVIDER_COORDINATE다. canonical 마스터 매핑 또는 공식 지하철 출입구가 검증됐다는 뜻이 아니다.
공식 좌표 artifact를 설정하면 기존 EXACT·방향·거리·유효기간 검증을 사용하며 실패를 provider 좌표로 덮어쓰지 않는다.

기본 활성화와 최대 10회 호출을 application/compose에 연결했다. 같은 좌표 요청은 한 평가에서 재사용한다.
키 자체와 권한은 환경에서 제공해야 한다. 서버가 변경된 설정을 사용하려면 별도 배포가 필요하다.
마스터 artifact는 기존 `/opt/ddubi/transit-master` 읽기 전용 마운트 안의 파일을 지정한다.

**미분류 잔여시간이 있는 실제 후보는 아직 도보를 교체할 수 없다.** 전체 잔여시간을 임의로 도보로 간주하거나
새 도보를 추가하여 이중 계산하지 않는다. 제공자의 비용 분해 근거/원본 fixture 확보 후 해당 분해 정책을 추가해야 한다.

## 3. 사용자 안내와 모델 승인 분리

`EvaluationGuidance`는 일정 응답과 Store.accept가 공통으로 사용하는 판정이다.
ETA 존재 + 탐색 완료 + 실제 시험 ML 미적용이면 기본 예상 제안을 수락할 수 있다.
DB에서는 평가 ID·입력 revision·이벤트 revision·상태·만료·미출발 여부를 추가로 검증한다.
모델 productionReady/productionEligible은 계속 별도로 보존하며 새 안내 필드로 모델을 승인하지 않는다.
기본 자동 알림 활성화 설정은 변경하지 않는다.

후보별 `candidateEstimates`에는 평가 시각의 순위·ETA·초 단위 소요시간·DB routeId·선택/제안 구분을 제공한다.
최초 선택은 입력 출발시각 P에서 평가한 최선 후보다. 이후 기존 선택은 수락 전까지 유지한다.
저장된 route.baseDurationMin은 원본이며 수정하지 않는다.

P+소요시간 < D이면 P 유지, 그렇지 않으면 D-600초 목표로 제한 탐색하는 기존 조건부 정책을 유지한다.
연속시간 전체의 최적해를 증명했다고 표시하지 않는다.

## 4. 호출 예산·유효기간·진단

- ML 배치 선조회는 실제 HTTP 청크마다 전체 마감과 ML 마감을 확인한다.
- 미호출 입력을 ML_BUDGET_EXHAUSTED로 캐시하여 뒤 후보가 동일 입력을 반복 호출하지 않는다.
- 보조 ML 호출 예산 부족만으로, 이미 provider fallback으로 계산한 전체 탐색을 실패시키지 않는다.
- 지하철은 canonical 노선·방향·승차역 인덱스로 후보를 줄인 뒤 기존 구간열·운행일·방향 검증을 수행한다.
- 출발 탐색 경계도 현재 후보와 관련된 지하철 운행만 사용한다.
- 원본 경로 예상 유효기간은 최대 10분 또는 다음 재계산시각이다. 실제 적용한 실시간 증거·도보 TTL이 짧으면 그 시각으로 제한한다.
- 증거 만료가 정기 갱신보다 먼저면 nextEvaluationAt도 앞당긴다. 이미 만료된 결과의 TTL은 늘리지 않고 15초 후 재시도한다.
- 시간에 사용하지 않은 normal/slow/unknown 구간 상태는 예상 전체를 만료시키지 않는다. blocked 증거의 만료는 보존한다.
- 재계산 감시 기준에 실제 계획 출발을 사용하여 일정 시작이 늦다는 이유로 출발 후에야 감시가 시작되지 않게 한다.
- 사용자 품질 결측 목록은 제안 후보의 canonical ID로 한정한다. 전체 후보 진단은 저장 evidence에 남는다.
- 사용한 시간 출처와 ML 수신 성공을 구별한다. ML_PRODUCTION도 segments에 정확한 출처를 표시한다.

## 5. 데이터·별도 실행 경로에 대한 한계

도로 근처 돌발정보는 여전히 POTENTIAL_IMPACT다. 확정 통제 제외는 검증된 canonical `segment.status=blocked` 입력을 사용한다.
단순히 사고 위치가 가깝다는 이유로 전체 버스 노선을 제외하거나 데이터를 정상으로 생성하지 않는다.
현재 운영 피드·시간표·권한의 존재는 이 코드 변경만으로 보장되지 않는다.

독립 경로 검색의 `BusJourneyEvaluator`는 BUS step 예측 미리보기다. 전체 여정 추천은 공통 v2 평가 API를 사용해야 한다.
FE가 원본 후보나 로컬 역산을 전체 추천처럼 보여주는 부분은 별도 전달 문서의 수정 대상이다.
호환 중인 구 API/레거시 계산기는 호출자를 확인하지 않은 채 삭제하지 않는다.

## 6. 검증

최종 로컬 결과: Backend 관련 테스트 143개 통과(실패·오류·생략 0), ML 테스트 63개 통과,
Backend 생성 응답 fixture 13개 JSON Schema 검증 통과, application/compose YAML 문법 검증 통과.

새 `EvaluationRecoveryTest`는 누락 도보 생성·API 장애·잔여시간 중복 방지·확정 통제 fallback·안내/모델 승인 분리·청크 예산을 검증한다.
기존 대기·출발·타임라인·도보·ML 적용 모드 테스트도 실행한다.
ML 테스트의 predict mock은 현재 timings 인수를 받아 MODEL_BUSY 503 경로를 실제로 검증한다.
v13 독립성 테스트는 로컬에 설치된 v12 모델과 무관하게 모델 없는 기동을 별도 프로세스로 재현한다.

로컬 MySQL/Redis 통합 테스트는 Docker 환경 미확보로 실행하지 못했다. 변경된 수락·최초 선택 규칙은
통합 테스트 기대값에도 반영했으며 실제 DB 트랜잭션 경계는 CI에서 확인해야 한다.
API 키를 사용하는 운영 카카오 호출과 실제 모바일 화면 검증은 수행하지 않았다.
