package com.restless.common.error;

import java.util.Map;
import lombok.Getter;

/**
 * 비즈니스 규칙 위반. {@link GlobalExceptionHandler}가 {@link ErrorResponse}로 바꾼다.
 * 클라이언트에 추가 정보를 줘야 하면 details를 넣는다 (예: {@code Map.of("seatIds", List.of(11))}).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
