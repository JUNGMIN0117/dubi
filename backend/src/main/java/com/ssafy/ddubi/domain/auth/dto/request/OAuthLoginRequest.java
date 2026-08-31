package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AUTH-003 소셜 로그인.
 *
 * redirectUri 를 클라이언트가 보내는 이유: 인가 코드를 토큰으로 교환할 때
 * 카카오가 "코드 발급에 쓴 redirect_uri 와 동일한가"를 대조한다.
 * 서버가 임의로 정하면 클라이언트가 실제로 쓴 값과 어긋나 교환이 실패한다.
 */
public record OAuthLoginRequest(
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @NotBlank(message = "authorizationCode 는 필수입니다.")
        String authorizationCode,

        @NotBlank(message = "redirectUri 는 필수입니다.")
        String redirectUri,

        String deviceId
) {
}
