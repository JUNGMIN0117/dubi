# Spark realtime 버스 대응표 캐시 수정 검증

2026-09-11. 운영 서버·인프라 설정은 변경하지 않았다.

## 변경

- `common/bus_crosswalk.py`: 큰 JSON 리터럴 대신 `spark.createDataFrame(rows, SCHEMA)` 사용.
- 후보 집계 뒤 `cache()` + `count()`로 스트리밍 시작 전에 인덱스를 물리화.
- 기본 MEMORY_AND_DISK 캐시를 사용한다. 21만 행을 무조건 broadcast하지 않는다.
- 기동 시 캐시 생성 실패와 전체 query 종료 시에만 `unpersist()`한다.
- SHA, masterVersion, canonical ID 검증과 중복 후보의 AMBIGUOUS 처리는 유지한다.
- 배치마다 만든 normalized/latest/rows 캐시와 정적 인덱스 캐시 수명은 다르다.

## 실제 크기 검증

입력: v6 인접 구간 105,070개 → 조회 행 210,140개 → 집계 인덱스 208,329개.
SHA: e7b8db4f3330d7275aa56b4674032d71aa6e34f99bec0c29cee9f7ce002676bd

Windows local[1], Python 3.11.9, Spark 3.5.3, Java 17에서:

- 대응표 테스트 5개 통과. ID 보존, 버전 불일치, 실제 크기 캐시와 반복 실행 계획 검사 포함.
- realtime/Redis/도로 영향 관련 테스트 31개 통과.
- 기동 물리화 약 6.02초.
- 100행 probe × 12회 조인: 매회 100개 후보 유지, 약 0.14~0.59초/action.
- 실행 계획에 `InMemoryTableScan` 확인. 거대 `from_json` 리터럴 없음.
- 반복 조인 동안 관측한 JVM heap 약 218~420 MiB. GC 이후 감소하며 계속 증가하지 않음.

Kafka 읽기·Redis 쓰기·운영 컨테이너를 포함한 성능이나 장시간 메모리 안정성의 증거는 아니다.
Windows Python 3.12 로컬 worker가 종료되어 별도 Python 3.11 테스트 환경으로 검증했다.
운영 Spark/Python 버전이나 시스템 Python 설치는 변경하지 않았다.

## 인프라 인계

인프라의 Spark 대응표 임시 OFF 조치는 이 변경에서 자동 해제하지 않는다.
수정 배포 후 동일 v6 파일·SHA로 Spark만 재활성화할 때 기동 캐시 생성 완료,
후속 배치 처리시간, driver heap, 컨테이너 restart count, 미매핑 사유를 확인한다.
Collector 쪽 기존 대응표와 버전은 그대로 유지한다.
