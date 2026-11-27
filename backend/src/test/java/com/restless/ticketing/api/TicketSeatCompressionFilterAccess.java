package com.restless.ticketing.api;

import jakarta.servlet.http.HttpServletRequest;

/** 다른 패키지의 필터 테스트가 미리 만든 gzip을 넘길 수 있게 한다. */
public final class TicketSeatCompressionFilterAccess {
    private TicketSeatCompressionFilterAccess() { }

    public static void offer(HttpServletRequest request, byte[] body, byte[] gzip) {
        TicketSeatCompressionFilter.offerPrecompressed(request, body, gzip);
    }
}
