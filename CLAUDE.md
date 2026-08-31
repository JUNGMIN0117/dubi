# DDUBI 프로젝트 컨텍스트 (Claude 작업 지침)

SSAFY 특화 프로젝트. 일정 기반으로 실시간 교통·날씨·행사를 분석해 출발 시각·경로·알림을 주는 서비스 "뚜비".
이 문서는 인프라 담당(이봉민)이 Cowork 세션에서 정리한 맥락의 인수인계본이다.

## 확정 스택
- Collector/Archiver: Python / Spark: PySpark(Structured Streaming + Batch)
- Backend·Notification: Java Spring Boot / Client: Flutter(Android+Web, React 폐기)
- Kafka(KRaft) / Redis / MySQL / HDFS+Parquet / nginx / Prometheus·Grafana
- DB 는 MySQL 로 확정(2026-08-27, PostGIS 폐기). 공간 교차 연산은 DB 가 아니라 수집·전처리 단계에서 segment_id 로 변환.
  MySQL 에는 회원·일정·추천·schedule_segment·provider ID↔segment_id 매핑을 저장
- 경로 후보는 외부 길찾기 API로 받고 Spring이 재평가 (후보는 Redis route:candidates 캐시)

## 아키텍처 (서버 2대, 각 16GB)
- 서버1(데이터): kafka, collector×5, archiver, hdfs-namenode+datanode1, spark master+worker×2, kafka-ui, node-exporter
- 서버2(서비스): nginx(80/443만 외부 공개), Flutter Web, backend, notification, mysql, redis, hdfs-datanode2, prometheus/grafana/kafka-exporter, node-exporter
- 분산 논리: 계산=서버1 안 논리적 분산(파티션3×컨슈머, worker 확장 실험), 저장=물리적 분산(HDFS 복제가 두 서버에 걸침)
- 데이터 흐름: collector→Kafka만 발행 / Kafka→Spark→Redis(실시간) / Kafka→Archiver→Parquet→HDFS(보관)

## 반드시 지킬 결정사항
1. **Kafka 보존 21일(504h)** — .env.example의 72h는 수정 대상. HDFS 정착(3단계) 후에만 단축
2. **HDFS 3단계 도입 순서**: ①Kafka 보존만 → ②archiver가 로컬 파일(Parquet) 적재 → ③HDFS 전환. **③까지 완료(2026-08-28)**.
   - archiver 는 WebHDFS(ARCHIVE_URI=webhdfs://hdfs-namenode:9870/ddubi/archive), Spark 체크포인트는 hdfs://hdfs-namenode:9000/ddubi/checkpoints.
   - 두 값 모두 환경변수라 HDFS 없이 로컬로 되돌리려면 .env 에서 file:///opt/ddubi/archive, /opt/ddubi/checkpoints 로 바꾸면 된다.
   - HDFS 컨테이너는 볼륨 권한 때문에 root 로 실행. 데이터노드는 호스트명(hdfs-datanode1/2)으로 등록·접근 (서버 분리 시 extra_hosts 필요)
   - HDFS kill switch 기산일: 2026-08-28 (2주 안에 불안정하면 위 로컬 값으로 되돌린다)
3. archiver는 1시간(또는 128MB) 단위 병합 Parquet 적재 (HDFS small files 방지)
4. 코어(compose→수집기→Kafka→Spark→Redis) 완성 전에 HDFS 착수 금지. HDFS kill switch: 시작 후 2주
5. 전 컨테이너 mem_limit + 로그 로테이션(10m×3) 적용. Kafka는 이중 리스너(내부 kafka:9092 / 호스트 localhost:29092), 기본 파티션 3
6. 주소·키는 전부 환경변수. .env는 절대 커밋 금지 (.env.example만 갱신)

## 저장소 관례 (기존 스캐폴드 따를 것)
- 네트워크명 ddubi-network, 서비스명 hdfs-namenode·mysql·redis·kafka 등 기존 볼륨/이름 유지
- HDFS 설정은 infra/hdfs/*.xml 마운트 방식, Spark는 infra/spark/Dockerfile(SPARK_IMAGE arg) 커스텀 이미지
- 환경변수 이름은 infra/.env.example의 기존 이름(KAFKA_BOOTSTRAP_SERVERS 등) 유지
- 데이터 계약은 docs/contracts/ (topics.yaml, redis-keys.yaml, events/*.schema.json)가 기준
- compose profile 그룹: core(kafka+kafka-ui) / collect / data(mysql+redis) / spark / archive / hdfs / monitoring

## 작업 순서 (현재 위치: 9번. 1~8 완료 2026-08-27~28)
1. ~~infra/docker-compose.yml 채우기~~ 2. ~~core 기동 검증~~ 3. ~~docs/contracts 검토~~
4. ~~수집기 1개→Kafka 관통~~ (weather, 기상청 API 허브 실데이터 확인) 5. ~~Spark→Redis~~ (streaming/weather_latest)
6. ~~archiver 파일 적재~~ 7. ~~HDFS 전환~~ (archiver WebHDFS + Spark 체크포인트 hdfs://)
8. ~~기준선 배치~~ (batch/weather_baseline: 아카이브 → 격자·요일·시간대 기준선 → HDFS /ddubi/baseline/weather. 실행: `docker compose --profile batch --profile spark run --rm spark-batch sh submit.sh batch/weather_baseline.py`)
   - 구간(segment) 기준선은 같은 틀(common/archive_source + 집계 함수)로 만들되 segment_id 설계·transport 수집기가 먼저 필요
   - 배치 실행기는 스트리밍이 남긴 워커 메모리 안(480m)에서 돈다. 워커 메모리를 늘리면 BATCH_EXECUTOR_MEMORY 도 올린다
9. **서버 이식** ← 다음(HTTPS 조기: Flutter는 평문 HTTP 차단, MySQL/Redis/Kafka 포트 비공개, 데이터노드 extra_hosts) → 10. GitLab CI → 11. 부하·확장 실험
- 순서 밖 미완: 수집기 bus/subway/traffic/event 구현, segment.status 계산 잡, Grafana 대시보드·exporter 스크랩, backend/notification/nginx compose 정의
- 알고 있는 개선점(미착수): weather 수집기가 격자를 공유하는 구마다 같은 관측을 따로 발행(25회 중 11회 중복). 격자 14개만 호출하고 payload 에 districts 목록을 담는 쪽으로 변경 예정 — 수집기·Spark PAYLOAD_SCHEMA·redis-keys weatherLatest 를 함께 고쳐야 함
- 팀 결정 대기: 좌표→segmentIds 변환 주체(수집기 vs Spark), Spark 가 provider↔segment 매핑을 읽는 방법, Kafka retention.bytes(1GiB/파티션) 조정, 스키마 버전 필드(schemaVersion) 추가 여부

## 작업 방식 규칙
- Git이 유일한 진실. 한 번에 하나 바꾸고 config→up→ps→logs 검증 후 커밋
- **커밋·푸시는 사용자가 직접 한다. Claude는 파일 수정까지만**
- 이미지 태그 latest 금지(동작 확인 후 버전 고정), 파일·폴더명 특수기호 없이 소문자
- 사용자는 짧고 자연스러운 한국어 설명을 선호하고, 요청한 것만 수행하기를 원함
