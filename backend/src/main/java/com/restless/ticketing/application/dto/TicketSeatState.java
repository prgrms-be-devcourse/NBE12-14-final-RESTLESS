package com.restless.ticketing.application.dto;

import com.restless.ticketing.domain.TicketSeatStatus;

/** 좌석 목록 최초 조회 이후 갱신하는 회차별 상태. */
public record TicketSeatState(Long seatId, TicketSeatStatus status) {
}
