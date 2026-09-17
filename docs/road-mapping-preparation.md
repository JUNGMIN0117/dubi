# 도로 대응 후보 생성과 운영 승인 경계

## 목적과 범위

보유 전국 도로망과 GTFS 버스 형상으로 방향을 고려한 도로 후보를 재현 가능하게 만든다. 전국 입력을 지원하며 지역 필터를 하드코딩하지 않는다. `--max-shapes` 기본값 0은 모든 형상을 처리한다. 초기 실행의 3개 형상 제한은 검증 실행 범위이며 기능/지역 지원 제한이 아니다.

이 도구는 **후보 생성기**다. `RoadImpactIndex` 운영 대응표가 아니며 승인된 대응표나 통제 판정을 생성했다고 해석하면 안 된다. 운영 활성화와 서버 쓰기는 없다.

## 확인된 원본과 기존 계약

- 다운로드 `2025-TM-KA-MR-LLV2 도로망(2024년 기준_지역).zip`의 링크 DBF에는 `LINK_ID`, `UP_FROM_NO`, `UP_TO_NODE`, `DOWN_FROM_`, `DOWN_TO_NO`, `ONEWAY`, `TRAF_ID_P`, `TRAF_ID_N`가 있다. 원본 속성은 후보에 그대로 보존한다.
- 원본 PRJ는 Korean_1985_Katech_TM128, 미터 단위다. 좌표를 경위도로 간주하지 않는다.
- collector/src/sources/kafka_adapter.py의 사고 제공자 namespace는 `SEOUL_ACCINFO_LINK`다. 원본 `TRAF_ID_P/N`와 동일한 체계라는 근거는 확인하지 못했다. 숫자 길이/값 일치만으로 namespace를 전환하지 않는다.
- 원본 `TRAF_ID_P/N` 중 0은 유효 대응 ID로 사용하지 않는다. P/N 방향 코드와 실제 형상 방향의 의미는 별도 검증 필요하다.
- sparkjobs/common/road_impact.py 운영 입력은 버스 crosswalk SHA·masterVersion, 노선/정류장 occurrence, COMPLETE 경로, 방향 검증, 증거·유효기간, namespaced roadLinks를 모두 요구한다. 본 후보를 해당 입력으로 직접 넣을 수 없다.

## 실행

Python 3.12에 `infra/scripts/road-requirements.txt`의 의존성을 별도 가상환경 또는 전용 디렉터리로 설치한다. 전역/번들 환경은 수정하지 않는다.

```powershell
python infra/scripts/road-mapping-candidates.py `
  --roads "C:\Users\SSAFY\Downloads\2025-TM-KA-MR-LLV2 도로망(2024년 기준_지역).zip" `
  --shapes "C:\Users\SSAFY\Downloads\source-gtfs-seoul-bus\shapes.txt" `
  --output "C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\road-mapping\run-unique" `
  --max-shapes 3
```

- 특정 형상을 처리하려면 `--shape-id`를 반복한다. 전체 입력은 `--max-shapes 0`이다.
- 기본 샘플 간격 40m, 후보 반경 50m, 모호성 점수차 8m는 **검토 후보 생성 파라미터**이지 승인 기준이 아니다.
- 변환은 `allow_ballpark=False`, 알려진 정확도 5m 이내, 필요한 grid 가용성을 요구한다. 기준 미충족 시 실패한다.
- 도로는 ZIP의 SHP/DBF에서 순차 읽고 필요한 형상 bounding box만 SQLite Rtree에 저장한다. 원본 ZIP 전체 해제나 전국 도로 객체 전체 메모리 적재를 하지 않는다.
- GTFS 좌표·sequence 중복/오류가 있는 형상은 조용히 이어 붙이지 않고 제외 사유를 보고한다.
- 후보는 가까운 폴리라인 조각의 거리와 국소 진행방향으로 정렬한다. multipart의 틈을 가짜 도로로 연결하지 않는다.
- 연속 샘플의 기하학적 끝점 연결, 모호한 평행도로, 반경 내 정렬된 후보 없음 상태를 구분한다. 이는 도로 위상/일방통행/고가·지하 검증을 대체하지 않는다.
- 체크섬 일치 `complete.json` 결과만 재사용한다. 손상/다른 설정/중단 출력은 덮어쓰지 않고 새 디렉터리를 요구한다. 부모 디렉터리 아래 형상별 배치 실행으로 완료 배치를 재사용할 수 있다.

## 산출물

- `roads.sqlite`: 원본 속성과 multipart 형상, 공간 인덱스.
- `candidates.jsonl`: 형상·형상 edge·진행거리별 상위 후보 3개와 방향/거리/연속성 상태. 항상 productionReady=false.
- `report.json`: 변환 연산·정확도, 범위, 상태 수, 원본 ID 계약의 미검증 조건, 형상별 커버리지.
- `complete.json`: 입력/스크립트/설정 fingerprint, 출력 해시, 완료 요약.

## 2026-09-17 실제 검증

결과: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\road-mapping\smoke-3-shapes-v1`

- 전국 도로 링크 629,716건 stream 검사, 선택 형상 영역 14,570건 공간 인덱스.
- 변환: Inverse of Korean 1985 to WGS 84 (1), 라이브러리 보고 정확도 1m. 원본 자체 위치 정확도의 보장은 아니다.
- 형상 3개: SB_SHAPE_100000020, SB_SHAPE_100000024, SB_SHAPE_100000025.
- 샘플 총 1,753개: 단일 우세 후보 1,053, 복수 모호 후보 479, 반경 내 정렬된 후보 없음 221.
- 별도 연속성 진단: 동일 링크 784, 기하학적 끝점 연결 204, 불연속 후보 33, 미평가 732. 샘플 분류 수와 합산하면 안 된다.
- **운영 승인 0건.** 후보 없음 221건을 임의의 최근접 링크로 채우지 않았다.
- candidate SHA256: `71975642dbc4f122eef6e5d3d8bb7ae23dce53dbca861955289cc09e208747b1`.
- report SHA256: `c5997639972e2b10513e09ae962dc21cf0495f124c78c14eb09444e15a88db5b`.
- 테스트 7개 통과: 거리·방향, multipart 틈, 평행도로 모호성, 장거리 샘플링, 방향별 연결, GTFS 오류 거부, 합성 SHP 전체 처리·검토용 출력·체크섬 재사용·손상 거부.

## 남은 작업과 다음 행동

남은 항목: 원본 도로 필드 사전의 방향/링크 체계 확인, GTFS 형상과 canonical 정류장 occurrence 연결, 전체 구간 연속 경로와 고가·지하/일방통행 검증, provider namespace 연결 근거, 유효기간 검증. 검증되지 않은 상태는 통제 미확인으로 유지한다.

다음 행동은 **보유 ZIP 내부 GIS DB 설명자료에서 TRAF_ID_P/N 및 방향 코드 의미를 확인해 후보의 링크 namespace·방향 검증을 진행하는 것**이다. 원본 추가 요청이나 운영 적용은 선행하지 않는다.
