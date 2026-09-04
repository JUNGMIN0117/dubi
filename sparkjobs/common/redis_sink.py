"""Spark 마이크로배치의 최신 상태를 TTL이 있는 Redis 문자열 키로 저장한다.

입력은 ``redisKey``와 ``redisValue``를 가진 DataFrame이며, 여러 자료의 갱신주기가 다른
잡은 ``ttlSeconds``도 함께 넘길 수 있다. Redis에는 재계산 가능한 원문을 쌓지 않고 최신
상태만 두며, 원문 보존은 Kafka를 소비하는 Archiver/HDFS가 맡는다.

쓰기 연결은 executor 파티션마다 하나만 열고 Redis pipeline으로 최대 500건을 묶는다.
마이크로배치 재시도 시 같은 키를 ``SET``으로 덮어쓰므로 결과는 멱등적이다. 일부 파티션의
Redis I/O가 실패하면 예외를 Spark로 돌려보내 checkpoint가 진행되지 않게 하며, 다음 재시도에서
이미 성공한 키도 같은 값으로 안전하게 갱신한다.
"""

from __future__ import annotations

from pyspark.sql import DataFrame

KEY_COL = "redisKey"
VALUE_COL = "redisValue"
TTL_COL = "ttlSeconds"
_PIPELINE_CHUNK = 500


def write_with_ttl(df: DataFrame, ttl_seconds: int, host: str, port: int, password: str | None) -> None:
    """모든 행에 동일한 TTL을 적용한다.

    날씨처럼 한 잡의 모든 키가 같은 갱신주기를 가질 때 사용한다. ``ttl_seconds``는 Redis의
    만료 초 단위이며 1 이상이어야 한다. 연결정보는 driver의 설정 객체가 아니라 기본형만
    closure에 넣어 executor 직렬화 문제와 비밀값의 불필요한 로그 노출을 피한다.
    """
    if ttl_seconds <= 0:
        raise ValueError("ttl_seconds는 1 이상이어야 합니다")

    def write_partition(rows) -> None:
        import redis  # executor 쪽에서 import

        client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
        pipe = client.pipeline(transaction=False)
        pending = 0
        for row in rows:
            pipe.set(row[KEY_COL], row[VALUE_COL], ex=ttl_seconds)
            pending += 1
            if pending >= _PIPELINE_CHUNK:
                pipe.execute()
                pending = 0
        if pending:
            pipe.execute()
        client.close()

    df.select(KEY_COL, VALUE_COL).foreachPartition(write_partition)


def write_with_row_ttl(df: DataFrame, host: str, port: int, password: str | None) -> None:
    """행별 ``ttlSeconds``를 사용해 갱신주기가 다른 최신 상태를 한 배치에서 저장한다.

    버스·지하철은 수십 초, 도로·도시 데이터는 수분마다 갱신되므로 하나의 고정 TTL을 쓰면
    빠른 자료가 지나치게 오래 남거나 느린 자료가 정상 수집 사이에 사라진다. 호출자는 계약에
    선언된 양의 TTL만 전달해야 하며, 이 함수도 executor에서 다시 검증해 잘못된 무기한 키가
    생성되는 것을 막는다.
    """

    def write_partition(rows) -> None:
        import redis  # executor 쪽에서 import

        client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
        pipe = client.pipeline(transaction=False)
        pending = 0
        for row in rows:
            ttl_seconds = int(row[TTL_COL])
            if ttl_seconds <= 0:
                client.close()
                raise ValueError("ttlSeconds는 1 이상이어야 합니다")
            pipe.set(row[KEY_COL], row[VALUE_COL], ex=ttl_seconds)
            pending += 1
            if pending >= _PIPELINE_CHUNK:
                pipe.execute()
                pending = 0
        if pending:
            pipe.execute()
        client.close()

    df.select(KEY_COL, VALUE_COL, TTL_COL).foreachPartition(write_partition)
