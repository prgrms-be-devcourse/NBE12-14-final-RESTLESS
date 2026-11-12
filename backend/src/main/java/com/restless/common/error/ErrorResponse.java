package com.restless.common.error;

/**
 * 오류 응답 본문. 클라이언트는 {@code code}로 분기하고 {@code message}는 화면 표시용이다.
 * API 계약(docs/contracts/api/README.md 3번)의 details, traceId, timestamp는 아직 없다.
 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message);
    }
}
