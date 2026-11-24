package com.restless.ticketing.application;

import com.restless.concert.application.PerformanceSeatCreator;
import com.restless.concert.application.dto.SeatSetupLayout;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceSeatCreatorAdapter implements PerformanceSeatCreator {
    private final TicketSeatInitializer initializer;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(Long performanceId, Long layoutId, List<SeatSetupLayout.Seat> seats, List<Price> prices) {
        initializer.initialize(performanceId, layoutId, seats, prices);
    }
}
