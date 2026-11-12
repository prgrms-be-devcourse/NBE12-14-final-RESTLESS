package com.restless.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 오류 코드. 응답의 {@code code}는 상수 이름이다.
 * 공통·인증·회원·티켓팅에서 사용하는 오류를 정의한다.
 */
@Getter
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "파일 크기가 너무 큽니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),

    // 인증, 회원
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    MEMBER_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 올바르지 않습니다."),
    MEMBER_HAS_ACTIVE_PARTICIPATION(HttpStatus.CONFLICT, "진행 중인 주문이나 참여가 있어 탈퇴할 수 없습니다."),

    // 티켓팅
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "선점할 수 없는 좌석이 포함되어 있습니다."),
    ACTIVE_RESERVATION_EXISTS(HttpStatus.CONFLICT, "진행 중인 예약이 있습니다."),
    TICKET_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "회차별 구매 한도를 초과했습니다."),
    BOOKING_NOT_OPEN(HttpStatus.CONFLICT, "예매 시작 전입니다."),
    BOOKING_CLOSED(HttpStatus.CONFLICT, "예매가 마감되었습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 키를 다른 요청에 사용할 수 없습니다."),
    SEAT_LOCK_TIMEOUT(HttpStatus.CONFLICT, "좌석 처리 경합이 발생했습니다. 같은 요청 키로 재시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return name();
    }
}
