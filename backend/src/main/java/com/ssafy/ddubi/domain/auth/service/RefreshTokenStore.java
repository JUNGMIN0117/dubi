package com.ssafy.ddubi.domain.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 리프레시 세션 저장소 (Redis).
 *
 * 키: {@code auth:refresh:{userId}:{deviceId}} — docs/contracts/redis-keys.yaml 계약.
 * 값: 현재 유효한 Refresh 토큰의 jti 하나. 회전할 때마다 덮어쓴다.
 *
 * <h3>회전과 재사용 감지</h3>
 * 클라이언트가 Refresh 로 재발급을 요청하면 새 jti 로 즉시 교체한다.
 * 이때 들고 온 jti 가 저장된 값과 다르면 <b>이미 회전된 옛 토큰</b>이라는 뜻이고,
 * 이는 토큰이 탈취되어 두 곳에서 쓰이고 있다는 신호다. 이 경우 해당 사용자의
 * 세션을 전부 폐기해 공격자와 정상 사용자를 함께 로그아웃시킨다(7장 계약).
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(Long userId, String deviceId) {
        return KEY_PREFIX + userId + ":" + deviceId;
    }

    /** 로그인·회전 시 현재 jti 를 저장한다. TTL 은 Refresh 유효기간과 맞춘다. */
    public void save(Long userId, String deviceId, String tokenId, Duration ttl) {
        redis.opsForValue().set(key(userId, deviceId), tokenId, ttl);
    }

    /**
     * 제시된 jti 가 현재 저장된 것과 같은지 확인한다.
     * 세션이 없거나(만료·로그아웃) 값이 다르면 false.
     */
    public boolean matches(Long userId, String deviceId, String tokenId) {
        String stored = redis.opsForValue().get(key(userId, deviceId));
        return stored != null && stored.equals(tokenId);
    }

    public boolean exists(Long userId, String deviceId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId, deviceId)));
    }

    /** 로그아웃 — 해당 기기 세션만 지운다. */
    public void revoke(Long userId, String deviceId) {
        redis.delete(key(userId, deviceId));
    }

    /**
     * 재사용 감지·탈퇴 시 사용자의 모든 기기 세션을 폐기한다.
     *
     * KEYS 대신 SCAN 을 쓴다 — KEYS 는 매칭이 끝날 때까지 Redis 를 블로킹하고,
     * 이 Redis 에는 Spark 가 쓰는 구간 상태도 함께 들어 있어 서비스 전체가 멈출 수 있다.
     */
    public long revokeAll(Long userId) {
        String pattern = KEY_PREFIX + userId + ":*";
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        log.warn("사용자 {} 의 리프레시 세션 {}건 폐기", userId, removed);
        return removed == null ? 0 : removed;
    }
}
