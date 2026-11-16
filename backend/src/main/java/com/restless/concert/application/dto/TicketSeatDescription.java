package com.restless.concert.application.dto;

import java.time.Instant;

/** 티켓에 표시할 현재 공연·회차·배치 정보. 가격과 티켓 상태는 티켓팅이 소유한다. */
public record TicketSeatDescription(Long performanceId, Long layoutId, Long concertId, String concertTitle,
                                    Instant startsAt, Long venueId, Long venueSeatId,
                                    String sectionLabel, String rowLabel, String seatNumber) { }
