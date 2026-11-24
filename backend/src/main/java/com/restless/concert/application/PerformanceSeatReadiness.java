package com.restless.concert.application;

import com.restless.concert.application.dto.SeatSetupLayout;
import java.util.List;

/** 공개 전 판매 좌석의 공통 설정 일치 여부를 검증한다. */
public interface PerformanceSeatReadiness {
    void requireReady(Long performanceId, Long layoutId, List<SeatSetupLayout.Seat> seats,
                      List<PerformanceSeatCreator.Price> prices);
}
