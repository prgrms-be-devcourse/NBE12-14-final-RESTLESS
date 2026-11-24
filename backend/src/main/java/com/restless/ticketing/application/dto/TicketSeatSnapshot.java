package com.restless.ticketing.application.dto;

import com.restless.ticketing.domain.TicketSeatStatus;

/** 판매 좌석 조회에 필요한 값만 담는다. */
public record TicketSeatSnapshot(Long id, Long layoutId, Long venueSeatId, String grade,
                                 long price, TicketSeatStatus status) {
}
