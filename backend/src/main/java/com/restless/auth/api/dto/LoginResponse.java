package com.restless.auth.api.dto;

/** 클라이언트는 이후 요청에 {@code Authorization: Bearer <accessToken>}을 붙인다. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    public static LoginResponse bearer(String accessToken, long expiresInSeconds) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
