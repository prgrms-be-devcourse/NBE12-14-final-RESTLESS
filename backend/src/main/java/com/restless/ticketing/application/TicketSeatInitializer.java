package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.PerformanceSeatCreator;
import com.restless.concert.application.dto.SeatSetupLayout;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketSeatInitializer {
    private final TicketSeatRepository seats;

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Long performanceId, Long layoutId, List<SeatSetupLayout.Seat> layoutSeats,
                           List<PerformanceSeatCreator.Price> prices) {
        Map<String, PerformanceSeatCreator.Price> bySection = prices.stream()
                .collect(Collectors.toMap(PerformanceSeatCreator.Price::sectionLabel, price -> price));
        var sections = layoutSeats.stream().map(SeatSetupLayout.Seat::sectionLabel).collect(Collectors.toSet());
        if (sections.isEmpty() || !sections.equals(bySection.keySet()) || prices.stream().anyMatch(price -> price.price() <= 0))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 구역 가격 설정이 완전하지 않습니다.");
        for (var seat : layoutSeats.stream().sorted(Comparator.comparing(SeatSetupLayout.Seat::venueSeatId)).toList()) {
            var price = bySection.get(seat.sectionLabel());
            seats.save(TicketSeat.createAvailable(performanceId, layoutId, seat.venueSeatId(), price.grade(), price.price()));
        }
    }
}
