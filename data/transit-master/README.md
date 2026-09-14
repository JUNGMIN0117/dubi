# 수도권 공통 마스터

원본 배포 파일은 이 폴더에서만 관리한다. 버스는 `bus/`, 지하철 마스터·좌표·출처는
`subway/`, 도로 대응표 상태는 `road/`에 둔다. 파일 이동으로 버전·내용·canonical ID는 바꾸지 않았다.
버스 생성 및 과거 검증 상세는 [기존 안내](../../backend/transit-master/README.md)를 참고한다.

`manifest.json`은 파일별 버전·압축 바이트 SHA-256·출처 문서·기준일과 호환 조합을 기록한다.
단일 기준일이 확인되지 않은 파일은 `referenceDate=null`이며 원천별 날짜를 출처 문서에서 확인한다.
버스 master와 crosswalk는 본문에 기록된 SHA/버전 관계까지 검증한다. 지하철은 독립 ID 공간이며
같은 배포 묶음에 포함됨을 뜻한다. 버스와 지하철 ID가 직접 대응한다는 의미는 아니다.

프로젝트 루트에서:

```powershell
python data/transit-master/manage.py check
python data/transit-master/manage.py env
# 컨테이너 안에서 사용할 경로로 환경값을 출력
python data/transit-master/manage.py env --mount-root /opt/ddubi/transit-master
```

Collector와 Spark에는 출력된 동일한 `BUS_CROSSWALK_PATH`, `BUS_CROSSWALK_SHA256`을 적용한다.
Backend에는 `DDUBI_METROPOLITAN_BUS_ARTIFACT`, `DDUBI_METROPOLITAN_BUS_SHA256`,
`DDUBI_TRANSIT_MAPPING_SUBWAY_MASTER_RESOURCE`를 적용한다. `check` 실패 시 배포하지 않는다.
갱신 시 원천/호환성을 검토하고 `manage.py build`로 manifest를 다시 만든 뒤 함께 배포한다.
`build`는 변경 파일을 신뢰하도록 manifest를 갱신하는 명령이므로 배포 검증 대신 사용하지 않는다.

Backend Maven은 `subway/`를 `target/classes/transit/`에 복사한다. 기존
`classpath:transit/metropolitan-subway-master.json.gz` 설정은 그대로 사용할 수 있다.
Maven 빌드 작업 공간에는 `backend/`와 형제 경로인 `data/transit-master/subway/`가 필요하다.
깨끗한 빌드에서 리소스 누락을 방지하려면 먼저 manifest 검증을 실행한다.

인프라 담당 전달 사항: 공통 폴더 전체를 `/opt/ddubi/transit-master:ro`로 마운트하고 위 env
출력값을 세 소비자에 전달한다. Backend만 복사하는 빌드 컨텍스트는 공통 subway 폴더도 포함해야 한다.
저장소 지침에 따라 Docker/infra/CI 파일은 수정하지 않았다. 기존 배포 경로에서 새 위치로
전환하는 것은 이 인터페이스를 적용한 후 진행한다.
