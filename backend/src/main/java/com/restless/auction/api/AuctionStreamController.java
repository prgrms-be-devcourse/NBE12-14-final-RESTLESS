package com.restless.auction.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 경매 실시간 상태 (SSE). 로그인이 필요하고 토큰은 연결할 때만 검사한다.
 * 연결 직후 현재 상태를 한 번 보내고, 이후 바뀔 때마다 최신 상태를 보낸다.
 */
@RestController
@RequiredArgsConstructor
public class AuctionStreamController {

    private final AuctionStreams streams;

    @GetMapping(path = "/api/auctions/{auctionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long auctionId) {
        return streams.subscribe(auctionId);
    }
}
