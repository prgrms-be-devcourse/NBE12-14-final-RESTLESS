package com.restless.concert.application;

import com.restless.concert.application.dto.SeatSetupLayout;
import java.util.List;

/** 회차 생성 트랜잭션에서 ticketing 좌석을 준비하는 계약. */
public interface PerformanceSeatCreator {
    void create(Long performanceId, Long layoutId, List<SeatSetupLayout.Seat> seats, List<Price> prices);

    record Price(String sectionLabel, String grade, long price) { }
}
