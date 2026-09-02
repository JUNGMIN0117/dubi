package com.ssafy.ddubi.domain.auth.client;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;

/**
 * 소셜 제공자에서 인가 코드를 사용자 정보로 바꾼다.
 *
 * 인터페이스로 둔 이유: authorizationCode 는 사람이 브라우저에서 로그인해야 나오는 값이라
 * 자동화 테스트에서 재현할 수 없다. 통합 테스트는 가짜 구현으로 갈아끼우고
 * 계정 통합 로직(도메인 규칙 3)만 검증한다.
 *
 * 구현체는 {@link AbstractOAuthClient} 를 상속해 제공자별 차이만 채운다.
 */
public interface OAuthClient {

    /** 이 구현이 담당하는 제공자. */
    AuthProvider provider();

    /** 인가 코드 → 액세스 토큰 → 사용자 정보. */
    OAuthUserInfo fetchUserInfo(OAuthCodeExchange exchange);
}
