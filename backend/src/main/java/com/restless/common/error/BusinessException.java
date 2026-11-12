package com.restless.common.error;

import lombok.Getter;

/** 비즈니스 규칙 위반. {@link GlobalExceptionHandler}가 {@link ErrorResponse}로 바꾼다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
