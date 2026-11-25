package com.restless.auction.api;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 경매 실시간 상태 (SSE). 로그인이 필요하고 토큰은 연결할 때만 검사한다.
 * 연결 직후 현재 상태를 한 번 보내고, 이후 바뀔 때마다 최신 상태를 보낸다.
 * 서버 전체 연결이 상한에 닿았으면 503 {@code AUCTION_STREAM_BUSY}와 {@code Retry-After}를 준다.
 */
@RestController
@RequiredArgsConstructor
public class AuctionStreamController {

    private final AuctionStreams streams;

    @GetMapping(path = "/api/auctions/{auctionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long auctionId, HttpServletResponse response) {
        try {
            return streams.subscribe(auctionId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.AUCTION_STREAM_BUSY) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(streams.retryAfterSeconds()));
            }
            throw e;
        }
    }
}
