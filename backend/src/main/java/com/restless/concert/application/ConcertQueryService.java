package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.concert.application.dto.BookingPerformance;
import com.restless.concert.application.dto.ConcertDetail;
import com.restless.concert.application.dto.ConcertPage;
import com.restless.concert.application.dto.PerformanceInfo;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.concert.application.dto.SeatPosition;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.ConcertRepository;
import com.restless.concert.infrastructure.PerformanceRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConcertQueryService {

    private final PerformanceRepository performanceRepository;
    private final LayoutSeatRepository layoutSeatRepository;
    private final ConcertRepository concertRepository;

    @Transactional(readOnly = true)
    public ConcertPage getList(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        var concerts = concertRepository.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new ConcertPage(concerts.stream().map(this::toDetail).toList(), concerts.getNumber(), concerts.getSize(),
                concerts.getTotalElements(), concerts.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ConcertDetail getDetail(Long concertId) {
        Concert concert = getConcert(concertId);
        return toDetail(concert);
    }

    private ConcertDetail toDetail(Concert concert) {
        return new ConcertDetail(concert.getId(), concert.getVenueId(), concert.getTitle(), concert.getStatus(),
                concert.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<PerformanceInfo> getPerformances(Long concertId) {
        getConcert(concertId);
        return performanceRepository.findAllByConcertIdOrderByStartsAtAsc(concertId).stream()
                .map(performance -> new PerformanceInfo(performance.getId(), performance.getConcertId(),
                        performance.getVenueId(), performance.getLayoutId(), performance.getStartsAt(),
                        performance.getBookingOpensAt(), performance.getBookingClosesAt(),
                        performance.getMaxTicketsPerUser(), performance.getStatus()))
                .toList();
    }

    private Concert getConcert(Long concertId) {
        if (concertId == null || concertId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다."));
    }

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
