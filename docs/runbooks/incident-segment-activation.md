# 버스 구간 도로 확인표와 돌발 자동 갱신

## 현재 준비 상태

2026-09-22 저장소의 `backend/tools/road-impact/validation/mapping-audit.json`에는
`operationalMappings=0`으로 기록되어 있다. `backend/transit-master/road/`에도 승인된 도로
crosswalk 및 OA-13310 링크 마스터가 없다. 따라서 **실제 최종 확인표와 Redis 운영 매핑은 아직 없다.**
감사 후보의 `LINK_ID` 겹침을 실제 버스 영향으로 발표하지 않는다.

## 승인 자료가 준비되면

1. 공식 LINK_ID와 AccInfo `LINK_ID`의 동일성, 버스 진행 방향, 경로 전체, 유효기간을 검토한다.
   검토 결과를 `backend/tools/road-impact/promote.py`의 입력으로 승격하고 OA-13310 링크
   마스터를 승인한다. SHA와 버전은 실제 생성 결과를 사용한다.
2. `sparkjobs/batch/export_bus_road_mapping.py`를 `--output`과 함께 실행한다.
   `review-table/` CSV의 `segmentId`, `mappingStatus`, `topisLinkIds`, occurrence 연결률,
   `evidenceRefs`, 적용기간을 검토한다. `RESOLVED`만 사고 역조회 대상이다.
3. Redis의 `ml:mapping:bus-road:active:{masterVersion}`가 새 대응표를 가리키고,
   `:meta`에 버스 crosswalk SHA와 승인 링크 마스터 버전이 일치하는지 확인한다.
4. Collector, Kafka, `spark-streaming-traffic-incident`, Redis, `ml-serving`을 실행한다.
   돌발 Silver의 GMS 추출은 `TRAFFIC_INCIDENT_AI_ENABLED=true`일 때 동작한다.
   구간 특성 API는 `ML_REDIS_LATEST_FEATURES_ENABLED=true`일 때 읽을 수 있다.
5. 별도 `ml-incident-feature-refresh` 서비스를 실행한다. 기본 15초마다 Redis의 완전한
   돌발 스냅샷을 확인한다. 새 회차면 승인된 도로 대응표에서 사고 링크가 포함된 구간을 찾아
   `OnlineFeatureAssembler`를 호출한다. 사고가 사라진 구간도 다시 계산한다.

```sh
docker compose --env-file infra/.env -f infra/docker-compose.yml --profile road-silver up -d \
  spark-streaming-traffic-incident ml-incident-feature-refresh
```

## 운영 확인

| 확인 | 기대 결과 |
|---|---|
| 새 전체 돌발 회차 | Worker 로그 `status=UPDATED`, `updated`는 영향 구간 수 |
| 같은 회차 반복 | `UNCHANGED`, GMS 캐시와 Redis 특성 중복 갱신 없음 |
| 사고가 사라진 다음 회차 | 이전 영향 구간도 재조립되고 사고 건수가 갱신됨 |
| 링크 마스터 또는 매핑 누락 | `MAPPING_UNAVAILABLE`/`MAPPING_NOT_VERIFIED`; 후보로 대체하지 않음 |
| 해당 구간 최신값 | `ml:feature:bus-segment:bus-segment-feature-v3:{masterVersion}:{segmentId}`의 `provenance.incident`와 `verifiedIncidentCount` 확인 |

최종 확인표를 만드는 배치와 자동 갱신 Worker는 **코드로 준비**되어 있다. 승인 자료 배포,
실제 서비스 실행, 운영 Redis/HDFS 적재 및 종단 검증은 아직 완료되지 않았다.
