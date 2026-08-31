package com.ssafy.ddubi.domain.auth.entity;

/**
 * 로그인 수단. v1 구현 범위는 LOCAL + KAKAO 이고 나머지는 스키마 자리만 잡아둔 것이다.
 * ENUM 값을 추가하려면 DDL 도 함께 고쳐야 한다(ALTER TABLE).
 */
public enum AuthProvider {
    LOCAL,
    KAKAO,
    GOOGLE,
    NAVER;

    public boolean isLocal() {
        return this == LOCAL;
    }
}
