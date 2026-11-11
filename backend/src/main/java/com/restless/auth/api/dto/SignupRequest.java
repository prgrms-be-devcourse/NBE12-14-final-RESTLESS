package com.restless.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 가입 요청.
 *
 * <p>비밀번호는 영문, 숫자, 특수문자(공백 제외 ASCII)만 8~64자다.
 * ASCII는 1글자가 1바이트라 BCrypt 한도(72바이트)를 넘지 않는다.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "영문, 숫자, 특수문자만 쓸 수 있습니다.")
        String password,
        @NotBlank @Size(min = 2, max = 30) String nickname) {
}
