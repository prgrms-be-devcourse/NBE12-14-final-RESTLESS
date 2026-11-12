package com.restless.common.error;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러 이후에 난 예외를 {@link ErrorResponse}로 바꾼다.
 * 필터 단계의 401, 403은 Security 설정의 JsonSecurityErrorHandler가 같은 모양으로 쓴다.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    /** 첫 필드 오류를 message로, 모든 필드 오류를 details.fields로 준다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, message, fields.isEmpty() ? null : Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        return respond(ErrorCode.INVALID_REQUEST);
    }

    /** 필수 헤더 누락 (예: 경쟁 요청의 Idempotency-Key). */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(ErrorCode.INVALID_REQUEST, "필수 헤더가 없습니다: " + e.getHeaderName(),
                Map.of("header", e.getHeaderName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return respond(ErrorCode.INVALID_REQUEST, "필수 값이 없습니다: " + e.getParameterName(),
                Map.of("parameter", e.getParameterName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
        return respond(ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 위에서 못 잡은 예외. Spring MVC 표준 예외(406 등)는 원래 상태 코드를 지키고,
     * 나머지는 500으로 답하며 traceId와 함께 로그를 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse standard
                && standard.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(standard.getStatusCode().value());
            return ResponseEntity.status(status)
                    .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, status.getReasonPhrase(), null, clock.instant()));
        }
        log.error("Unhandled exception", e);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code) {
        return respond(code, code.getMessage(), null);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(ErrorResponse.of(code, message, details, clock.instant()));
    }
}
