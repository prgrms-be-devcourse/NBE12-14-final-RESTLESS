package com.restless.member.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 탈퇴 요청. 토큰을 탈취당해도 남이 탈퇴시키지 못하게 비밀번호를 다시 받는다.
 * 결제 완료 주문은 탈퇴를 막지 않는다. 그래서 탈퇴 뒤 그 주문을 조회하거나 환불 요청할 수 없다는 안내에 동의를 받는다.
 * 빼거나 false면 400이다.
 */
public record WithdrawRequest(
        @NotBlank String password,
        @NotNull(message = NOTICE_REQUIRED) @AssertTrue(message = NOTICE_REQUIRED) Boolean paidOrderNoticeAgreed) {

    static final String NOTICE_REQUIRED = "결제 완료 주문 안내에 동의해야 탈퇴할 수 있습니다.";
}
