package com.restless.ticketing.application.dto;

/** 조회 시점의 한도와 활성 예약. 선점 시에는 서버가 다시 검증한다. */
public record TicketBookingAvailability(int maximum, int confirmed, int reserved, int remaining,
                                        TicketReservationDetail activeReservation) { }
