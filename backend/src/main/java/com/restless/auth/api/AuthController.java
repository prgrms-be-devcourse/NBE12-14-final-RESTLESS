package com.restless.auth.api;

import com.restless.auth.api.dto.LoginRequest;
import com.restless.auth.api.dto.LoginResponse;
import com.restless.auth.api.dto.SignupRequest;
import com.restless.auth.api.dto.SignupResponse;
import com.restless.auth.application.AuthService;
import com.restless.auth.application.AuthService.LoginResult;
import com.restless.auth.infrastructure.RefreshTokenProperties;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 *
 * <p>Access Token은 응답 본문으로 주고, 이후 {@code Authorization: Bearer} 헤더로 받는다.
 * Refresh Token은 HttpOnly 쿠키(경로 /api/auth)로만 주고받는다. 자바스크립트는 읽을 수 없다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenProperties refreshProperties;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return new SignupResponse(authService.signup(request.email(), request.password(), request.nickname()));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return withTokens(authService.login(request.email(), request.password()));
    }

    /** Refresh 쿠키로 새 Access Token과 새 Refresh 쿠키를 받는다. 실패하면 401 INVALID_REFRESH_TOKEN. */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = "${restless.auth.refresh.cookie-name:refresh_token}", required = false) String refreshToken) {
        return withTokens(authService.refresh(refreshToken));
    }

    /** 이 기기의 Refresh Token을 폐기하고 쿠키를 지운다. Access Token은 만료(1시간)까지 남는다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "${restless.auth.refresh.cookie-name:refresh_token}", required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<LoginResponse> withTokens(LoginResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), refreshProperties.ttl()).toString())
                .body(LoginResponse.bearer(result.accessToken().value(), result.accessToken().expiresInSeconds()));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(refreshProperties.cookieName(), value)
                .httpOnly(true)
                .secure(refreshProperties.cookieSecure())
                .sameSite(refreshProperties.sameSite())
                .path(RefreshTokenProperties.COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
