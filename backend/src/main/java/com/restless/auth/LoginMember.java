package com.restless.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 회원의 ID를 컨트롤러 파라미터로 받는다. 다른 도메인은 이것만 쓴다.
 *
 * <pre>{@code
 * @PostMapping("/api/fan-meetings/{eventId}/applications")
 * public ApplyResponse apply(@LoginMember Long userId, @PathVariable Long eventId) { ... }
 * }</pre>
 *
 * <p>인증이 필요한 경로에서만 쓴다. 토큰이 없으면 401(UNAUTHORIZED)이다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginMember {
}
