package com.restless.concert.application;

/** 티켓팅 도메인이 제공하는 회차별 과거 선점 존재 여부. */
public interface ReservationHistory {
    boolean hasSeatHold(Long performanceId);
}
