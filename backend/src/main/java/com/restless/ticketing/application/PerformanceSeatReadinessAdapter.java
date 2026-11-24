package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.PerformanceSeatCreator;
import com.restless.concert.application.PerformanceSeatReadiness;
import com.restless.concert.application.dto.SeatSetupLayout;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceSeatReadinessAdapter implements PerformanceSeatReadiness {
    private final TicketSeatRepository seats;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireReady(Long performanceId, Long layoutId, List<SeatSetupLayout.Seat> expected,
                             List<PerformanceSeatCreator.Price> prices) {
        Map<String, PerformanceSeatCreator.Price> bySection = prices.stream()
                .collect(Collectors.toMap(PerformanceSeatCreator.Price::sectionLabel, Function.identity()));
        var actual = seats.findAllByPerformanceId(performanceId);
        Map<Long, TicketSeat> bySeat = actual.stream().collect(Collectors.toMap(TicketSeat::getVenueSeatId, Function.identity()));
        boolean valid = !expected.isEmpty() && actual.size() == expected.size() && expected.stream().allMatch(seat -> {
            var price = bySection.get(seat.sectionLabel());
            var current = bySeat.get(seat.venueSeatId());
            return price != null && current != null && layoutId.equals(current.getLayoutId())
                    && price.grade().equals(current.getGrade()) && price.price() == current.getPrice();
        });
        if (!valid) throw new BusinessException(ErrorCode.INVALID_REQUEST, "회차의 판매 좌석과 공통 가격이 일치하지 않습니다.");
    }
}
