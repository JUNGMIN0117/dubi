package com.ssafy.ddubi.domain.auth.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.user.entity.User;

import java.util.List;

/**
 * 인증 응답에 포함되는 사용자 요약.
 *
 * 명세서 원문은 {@code provider} 단일값이었으나 ERD v2 확정 변경 2번에 따라
 * {@code providers} 배열이다 — 한 계정에 LOCAL 과 KAKAO 가 함께 붙을 수 있기 때문이다.
 */
public record AuthUserResponse(
        Long id,
        List<AuthProvider> providers,
        String email,
        String nickname
) {

    public static AuthUserResponse of(User user, List<AuthProvider> providers) {
        return new AuthUserResponse(user.getId(), providers, user.getEmail(), user.getNickname());
    }
}
