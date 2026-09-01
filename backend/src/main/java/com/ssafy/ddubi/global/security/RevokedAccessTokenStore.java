package com.ssafy.ddubi.global.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 즉시 무효화해야 하는 사용자 목록 (Redis).
 *
 * <h3>왜 필요한가</h3>
 * JWT 는 자체 검증이라 서버가 "이 토큰 취소"라고 말할 방법이 없다. 리프레시 세션을 지워도
 * 이미 발급된 <b>액세스 토큰은 만료(60분) 전까지 그대로 통과</b>한다.
 *
 * 탈퇴한 사용자의 토큰이 살아 있으면 단순히 조회가 되는 정도가 아니라,
 * 쓰기 API 에서 사라진 user_id 로 INSERT 를 시도해 FK 위반 500 이 난다.
 *
 * <h3>동작</h3>
 * 무효화 시점에 사용자 id 를 액세스 토큰 유효기간만큼만 담아둔다. 그 시간이 지나면
 * 어차피 모든 토큰이 만료되므로 키도 함께 사라진다 — 목록이 무한정 자라지 않는다.
 *
 * 키: {@code auth:revoked:{userId}} — docs/contracts/redis-keys.yaml
 */
@Component
public class RevokedAccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenStore.class);

    private static final String KEY_PREFIX = "auth:revoked:";
    private static final String MARKER = "1";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RevokedAccessTokenStore(StringRedisTemplate redis, JwtProperties jwtProperties) {
        this.redis = redis;
        // 액세스 토큰이 살아 있는 동안만 막으면 된다.
        this.ttl = jwtProperties.accessTokenValidity();
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /** 탈퇴·토큰 탈취 감지 시 호출. 이 사용자의 기존 액세스 토큰이 즉시 통하지 않는다. */
    public void revoke(Long userId) {
        redis.opsForValue().set(key(userId), MARKER, ttl);
        log.info("액세스 토큰 무효화 — userId={} ({}분간)", userId, ttl.toMinutes());
    }

    public boolean isRevoked(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    /** 재로그인하면 다시 정상 사용자다 — 무효화 표시를 지운다. */
    public void clear(Long userId) {
        redis.delete(key(userId));
    }
}
