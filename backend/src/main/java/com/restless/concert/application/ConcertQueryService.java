package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.Performance;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.PerformanceRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConcertQueryService {

    private final PerformanceRepository performanceRepository;
    private final LayoutSeatRepository layoutSeatRepository;

    @Transactional(readOnly = true)
    public PerformanceLayout getPerformanceLayout(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다."));
        List<SeatPosition> seats = layoutSeatRepository.findAllById_LayoutId(performance.getLayoutId()).stream()
                .map(seat -> new SeatPosition(seat.getId().getVenueSeatId(), seat.getSectionLabel(),
                        seat.getRowLabel(), seat.getSeatNumber(), seat.getPosX(), seat.getPosY()))
                .toList();
        return new PerformanceLayout(performance.getLayoutId(), seats);
    }

    public record PerformanceLayout(Long layoutId, List<SeatPosition> seats) {
        public PerformanceLayout {
            seats = List.copyOf(seats);
        }
    }

    public record SeatPosition(Long venueSeatId, String sectionLabel, String rowLabel,
                               String seatNumber, BigDecimal posX, BigDecimal posY) {
    }
}
