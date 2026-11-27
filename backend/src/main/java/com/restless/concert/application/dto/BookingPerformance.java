package com.restless.concert.application.dto;

import java.time.Instant;

/** 선점에 필요한 회차 정책. 판매 가능 좌석은 잠근 좌석 기준으로 ConcertQueryService.sellableVenueSeatIds에서 확인한다. */
public record BookingPerformance(Long layoutId, Instant bookingOpensAt, Instant bookingClosesAt,
                                 int maxTicketsPerUser) {
}
