package com.restless.ticketing.infrastructure;

import java.time.Instant;

/** 본인 예약으로 소유권을 제한한 티켓·판매 좌석 조회 결과. */
public record IssuedTicketRow(Long ticketId, String ticketNo, Long reservationId, Long orderId,
                              Long performanceId, Long seatId, Long venueSeatId, Long layoutId, String grade,
                              long priceAmount, String status, Instant issuedAt, Instant usedAt, Instant cancelledAt) { }
