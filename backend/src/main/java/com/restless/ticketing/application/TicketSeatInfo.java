package com.restless.ticketing.application;

import com.restless.ticketing.domain.TicketSeatStatus;
import java.math.BigDecimal;

/** 지정 회차의 판매 좌석과 배치 위치를 결합한 조회 결과. */
public record TicketSeatInfo(Long seatId, Long venueSeatId, String sectionLabel, String rowLabel,
                             String seatNumber, BigDecimal posX, BigDecimal posY,
                             String grade, long price, TicketSeatStatus status) {
}
