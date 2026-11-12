package com.restless.auth.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh Token 설정. 쿠키는 HttpOnly이고 /api/auth 경로에만 실린다.
 *
 * @param ttl          유효 기간 (기본 7일)
 * @param cookieName   쿠키 이름
 * @param cookieSecure HTTPS에서만 보내기. 로컬 http://localhost에서도 브라우저가 허용한다
 * @param sameSite     Strict면 다른 사이트에서 시작한 요청에는 쿠키를 싣지 않는다(CSRF 방어)
 */
@ConfigurationProperties(prefix = "restless.auth.refresh")
public record RefreshTokenProperties(Duration ttl, String cookieName, Boolean cookieSecure, String sameSite) {

    public static final String COOKIE_PATH = "/api/auth";

    public RefreshTokenProperties {
        if (ttl == null) {
            ttl = Duration.ofDays(7);
        }
        if (cookieName == null || cookieName.isBlank()) {
            cookieName = "refresh_token";
        }
        if (cookieSecure == null) {
            cookieSecure = true;
        }
        if (sameSite == null || sameSite.isBlank()) {
            sameSite = "Strict";
        }
    }
}
