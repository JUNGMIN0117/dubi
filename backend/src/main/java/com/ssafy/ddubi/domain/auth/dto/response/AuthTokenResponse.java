package com.ssafy.ddubi.domain.auth.dto.response;

/**
 * AUTH-001~004 공통 응답. 명세서 v2.1.1 의 "토큰 묶음과 user 정보".
 *
 * deviceId 를 돌려주는 이유: 클라이언트가 안 보내면 서버가 발급하는데,
 * 이후 재발급·로그아웃에서 같은 값을 써야 세션이 맞는다.
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInSec,
        long refreshTokenExpiresInSec,
        String deviceId,
        AuthUserResponse user
) {

    public static final String BEARER = "Bearer";

    public static AuthTokenResponse of(String accessToken, String refreshToken,
                                       long accessExpiresInSec, long refreshExpiresInSec,
                                       String deviceId, AuthUserResponse user) {
        return new AuthTokenResponse(accessToken, refreshToken, BEARER,
                accessExpiresInSec, refreshExpiresInSec, deviceId, user);
    }
}
