package com.ssafy.ddubi.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 사용자의 id 를 컨트롤러 파라미터로 받는다.
 *
 * <pre>
 * public ApiResponse&lt;UserResponse&gt; me(@LoginUserId Long userId) { ... }
 * </pre>
 *
 * 뚜비는 모든 사용자 소유 리소스에 소유권 검사가 붙으므로(도메인 규칙 1)
 * 거의 모든 컨트롤러가 userId 를 필요로 한다. SecurityContextHolder 를 매번 파는 대신 이걸 쓴다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}
