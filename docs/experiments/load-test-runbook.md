# 부하·확장 실험 절차서 (11단계)

증명할 명제 두 가지:
1. **계산의 분산** — Spark worker 를 1→2→3 으로 늘리면 같은 부하를 더 빨리 소화한다
2. **저장의 분산** — 데이터노드 하나가 죽어도 아카이브를 읽을 수 있다

도구: `replay-producer`(부하 발생), `spark-streaming-replay`(실험용 소비자), Grafana(측정), Spark Master UI(재배치 관찰)

## 구성 원칙

- 실험은 **전용 재생 토픽 `city.replay`** 로만 한다. 운영 토픽·아카이브·운영 Redis 키를 건드리지 않는다
- 실험용 스트리밍은 운영 잡과 **같은 코드**를 환경변수만 바꿔 띄운다 (Redis 키 `weather:replay:*`, 체크포인트 `weather_replay`, 컨슈머 그룹 `spark-weather-replay`)
- 실험 중에는 **운영 스트리밍(spark-streaming-weather)을 멈춘다** — 워커 자원(각 1536m)을 실험 잡이 전부 쓰게 하고, 측정에 잡음이 섞이지 않게. 실험 후 다시 켠다 (Redis TTL 2h 라 서비스 영향 없음)
- 처리량 상한(`maxOffsetsPerTrigger`)은 실험 잡에서 크게(200,000/트리거) 풀어둔다. 기본 10,000 이면 초당 1,000건에서 막혀 worker 수 차이가 안 보인다

## 측정 지표

| 지표 | 어디서 | 뜻 |
|---|---|---|
| 랙 소진 시간 | Grafana "컨슈머 랙" 패널 (`spark-weather-replay` 그룹) | replay 종료 후 랙이 0 이 되기까지의 초. **핵심 지표** |
| 처리량 (건/초) | 총 건수 ÷ 소진 시간, 또는 Spark UI(4041) 배치별 입력 건수 | worker 수별 비교 |
| 배치 처리시간 | Spark UI(4041) Structured Streaming 탭 | 트리거 10s 대비 얼마나 걸리나 |
| 실행기 재배치 | Spark Master UI(8080) | worker kill 후 실행기가 옮겨가는 시각 |

## 실험 절차 (서버1, `~/S15P21E202/infra`)

모든 compose 명령은 `-f docker-compose.yml -f docker-compose.server1.yml` 을 붙인다 (아래 `DC` 로 약칭).

```
DC="docker compose -f docker-compose.yml -f docker-compose.server1.yml"
```

### 0. 준비 (실험 세션당 1회)
```
$DC stop spark-streaming-weather                 # 운영 스트리밍 잠시 중지
$DC --profile experiment up -d spark-streaming-replay
docker logs -f spark-streaming-replay            # "started topic=city.replay" 확인 후 Ctrl+C
```
PC 에서 터널: `ssh -L 3000:localhost:3000 j15e202.p.ssafy.io`(Grafana), `ssh -L 8080:localhost:8080 -L 4041:localhost:4041 j15e202a.p.ssafy.io`

### 1. 회차 시작 전 — 토픽 초기화 (매 회차)
```
$DC --profile core exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic city.replay
$DC --profile core exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic city.replay --partitions 3
```
(삭제 직후 생성이 거부되면 몇 초 기다렸다 재시도)

### 2. worker 수 설정
```
$DC stop spark-worker-2            # 1대 조건
$DC start spark-worker-2           # 2대 조건 (기본)
# 3대 조건: compose 에 spark-worker-3 블록을 임시 추가하거나 SPARK_WORKER_MEMORY 를 낮춰 자원 확보 (별도 안내)
```
Master UI(8080)에서 worker 수와 실행기 배치가 바뀐 것을 확인하고 진행한다.

### 3. 부하 투입 (동일 파라미터 유지)
```
$DC --profile replay run --rm replay-producer python -m replay \
  --source-topic city.raw --target-topic city.replay \
  --from-date 2026-08-31 --to-date 2026-09-02 --rate 2000 --limit 20000
```
시작 시각과 "완료" 로그 시각을 기록한다.

### 4. 측정
- Grafana 랙 패널에서 `spark-weather-replay` 의 랙이 0 으로 돌아온 시각 → **소진 시간 = 그 시각 − replay 완료 시각**
- Spark UI(4041) 에서 배치 처리시간 평균 기록
- 같은 조건 **3회 반복**해 평균

### 5. 장애 시연 (worker kill)
부하가 걸린 상태(랙 > 0)에서:
```
$DC stop spark-worker-2     # Master UI: 실행기가 사라지고 남은 worker 에 재배치되는 것을 관찰
$DC start spark-worker-2    # 복구
```
멈추지 않고 느려지기만 하는 것, 그리고 복구 후 속도가 돌아오는 것을 랙 그래프로 확인.

### 6. 저장 분산 시연 (datanode 정지)
서버2에서 `docker stop hdfs-datanode2` → 서버1에서 replay `--dry-run` 이 아카이브를 정상 읽는지 확인 → `docker start hdfs-datanode2`.
NameNode UI(9870) Datanode 탭에서 Live 1→2 로 돌아오는 것까지.

### 7. 정리 (실험 세션 종료)
```
$DC --profile experiment stop spark-streaming-replay
$DC start spark-streaming-weather                # 운영 스트리밍 복귀
$DC start spark-worker-2                          # worker 원상복구
```
운영 복귀 확인: 서버2 Redis 의 `weather:latest:*` TTL 이 다음 수집 사이클에 갱신되는지.

## 기록 양식

| 회차 | worker | rate | limit | replay 완료 | 랙 0 도달 | 소진(s) | 처리량(건/s) | 배치 평균(s) |
|---|---|---|---|---|---|---|---|---|
| 1-1 | 1 | 2000 | 20000 | | | | | |
| 1-2 | 1 | | | | | | | |
| 1-3 | 1 | | | | | | | |
| 2-1 | 2 | | | | | | | |
| ... | | | | | | | | |

## 주의

- 파티션이 3개라 소비 병렬성 상한도 3. worker 4대 이상은 의미 없다 (실험 범위 1~3 인 이유)
- 부하가 작으면 차이가 안 난다. 랙이 수천 건 이상 쌓이는 rate 를 시험 실행으로 먼저 찾는다
- replay 첫 실행은 Kafka 커넥터·JVM 예열로 느릴 수 있다. 본 측정 전 예열 1회
- `-f` 오버레이를 빠뜨리면 데이터노드2 이름 해석 실패로 replay 가 죽는다
