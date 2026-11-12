package com.restless.member.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 탈퇴 요청. 토큰을 탈취당해도 남이 탈퇴시키지 못하게 비밀번호를 다시 받는다. */
public record WithdrawRequest(@NotBlank String password) {
}
