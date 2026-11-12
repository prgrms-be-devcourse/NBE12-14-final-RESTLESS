package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.concert.application.dto.BookingPerformance;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.concert.application.dto.SeatPosition;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.Performance;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.PerformanceRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    /** 선점에 필요한 회차 정책과 판매 가능한 배치 좌석만 제공한다. */
    @Transactional(readOnly = true)
    public BookingPerformance getBookingPerformance(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다."));
        Set<Long> sellableSeatIds = layoutSeatRepository.findAllById_LayoutId(performance.getLayoutId()).stream()
                .filter(seat -> seat.isSellable())
                .map(seat -> seat.getId().getVenueSeatId())
                .collect(Collectors.toUnmodifiableSet());
        return new BookingPerformance(performance.getLayoutId(), performance.getBookingOpensAt(),
                performance.getBookingClosesAt(), performance.getMaxTicketsPerUser(), sellableSeatIds);
    }


}
