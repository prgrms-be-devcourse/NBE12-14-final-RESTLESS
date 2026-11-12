package com.restless.common.error;

import com.restless.common.web.TraceIdFilter;
import java.time.Instant;
import java.util.Map;

/**
 * 오류 응답 본문. 형식은 API 계약(docs/contracts/api/README.md 공통 규칙 3)을 따른다.
 * <ul>
 *   <li>{@code code}: 클라이언트가 분기하는 값. {@link ErrorCode} 상수 이름</li>
 *   <li>{@code message}: 화면 표시용</li>
 *   <li>{@code details}: 오류별 추가 정보. 없으면 null</li>
 *   <li>{@code traceId}: 서버 로그에서 이 요청을 찾는 값 ({@link TraceIdFilter})</li>
 *   <li>{@code timestamp}: 오류가 난 시각 (UTC)</li>
 * </ul>
 */
public record ErrorResponse(String code, String message, Map<String, Object> details, String traceId,
                            Instant timestamp) {

    public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details, Instant now) {
        return new ErrorResponse(errorCode.getCode(), message, details, TraceIdFilter.current(), now);
    }

    public static ErrorResponse of(ErrorCode errorCode, Instant now) {
        return of(errorCode, errorCode.getMessage(), null, now);
    }
}
