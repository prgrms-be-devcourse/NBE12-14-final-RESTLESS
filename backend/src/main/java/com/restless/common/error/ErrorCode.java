package com.restless.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 오류 코드. 응답의 {@code code}는 상수 이름이다.
 * 지금은 공통 코드와 인증에 필요한 코드만 있다. 도메인 코드는 `도메인_상황` 이름으로 추가한다.
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

    // 경매, 크레딧
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "경매를 찾을 수 없습니다."),
    AUCTION_FORBIDDEN(HttpStatus.FORBIDDEN, "이 경매를 변경할 권한이 없습니다."),
    AUCTION_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작된 경매는 수정하거나 삭제할 수 없습니다."),
    AUCTION_NOT_OPEN(HttpStatus.CONFLICT, "아직 시작하지 않은 경매입니다."),
    AUCTION_CLOSED(HttpStatus.CONFLICT, "종료된 경매입니다."),
    BID_BY_SELLER(HttpStatus.CONFLICT, "자신의 경매에는 입찰할 수 없습니다."),
    BID_ALREADY_HIGHEST(HttpStatus.CONFLICT, "이미 현재 최고 입찰자입니다."),
    BID_AMOUNT_TOO_LOW(HttpStatus.CONFLICT, "최소 입찰 가능 금액보다 낮습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 키로 다른 요청을 보냈습니다."),
    CREDIT_INSUFFICIENT(HttpStatus.CONFLICT, "사용 가능한 크레딧이 부족합니다.");

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
