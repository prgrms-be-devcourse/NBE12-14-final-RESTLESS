package com.restless.concert.application.dto;

import java.math.BigDecimal;

public record SeatPosition(Long venueSeatId, String sectionLabel, String rowLabel,
                           String seatNumber, BigDecimal posX, BigDecimal posY) {
}
