package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.common.observability.SeatQueryMetrics;
import com.restless.concert.application.dto.*;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.infrastructure.ConcertRepository;
import com.restless.concert.infrastructure.LayoutSeatPositionRepository;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.PerformanceRepository;
import com.restless.concert.infrastructure.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConcertQueryService {

    private final PerformanceRepository performanceRepository;
    private final LayoutSeatRepository layoutSeatRepository;
    private final LayoutSeatPositionRepository layoutSeatPositions;
    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final SeatQueryMetrics seatQueryMetrics;

    /** 페이지에 포함된 회차·물리 좌석만 일괄 조회한다. 다른 도메인에 Repository를 노출하지 않는다. */
    @Transactional(readOnly = true)
    public List<TicketSeatDescription> getTicketSeatDescriptions(Set<Long> performanceIds, Set<Long> venueSeatIds) {
        if (performanceIds.isEmpty() || venueSeatIds.isEmpty()) {
            return List.of();
        }
        return performanceRepository.findTicketSeatDescriptions(performanceIds, venueSeatIds);
    }

    @Transactional(readOnly = true)
    public ConcertPage getList(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        var concerts = concertRepository.findAllByPublishedTrue(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var venueIds = concerts.stream().map(Concert::getVenueId).collect(Collectors.toSet());
        Map<Long, Venue> venues = venueRepository.findAllById(venueIds).stream()
                .collect(Collectors.toMap(Venue::getId, venue -> venue));
        return new ConcertPage(concerts.stream().map(concert -> toDetail(concert, venues.get(concert.getVenueId()))).toList(), concerts.getNumber(), concerts.getSize(),
                concerts.getTotalElements(), concerts.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ConcertDetail getDetail(Long concertId) {
        Concert concert = getPublicConcert(concertId);
        Venue venue = venueRepository.findById(concert.getVenueId())
                .orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        return toDetail(concert, venue);
    }

    private ConcertDetail toDetail(Concert concert, Venue venue) {
        if (venue == null) throw new IllegalStateException("공연장이 없습니다.");
        return new ConcertDetail(concert.getId(), concert.getVenueId(), concert.getTitle(), concert.getStatus(),
                concert.getCreatedAt(), venue.getName(), venue.getAddress(), concert.getDescription(),
                concert.getPeriodStartDate(), concert.getPeriodEndDate(), concert.getDurationMinutes());
    }

    @Transactional(readOnly = true)
    public List<PerformanceInfo> getPerformances(Long concertId) {
        getPublicConcert(concertId);
        return performanceRepository.findAllByConcertIdOrderByStartsAtAsc(concertId).stream()
                .map(performance -> new PerformanceInfo(performance.getId(), performance.getConcertId(),
                        performance.getVenueId(), performance.getLayoutId(), performance.getStartsAt(),
                        performance.getBookingOpensAt(), performance.getBookingClosesAt(),
                        performance.getMaxTicketsPerUser(), performance.getStatus()))
                .toList();
    }

    private Concert getPublicConcert(Long concertId) {
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return concertRepository.findByIdAndPublishedTrue(concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public void requirePerformance(Long performanceId) {
        if (!performanceRepository.existsById(performanceId) || !concertRepository.isPerformancePublished(performanceId).orElse(false)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다.");
        }
    }

    /** 고객 좌석 조회용 배치. 없는 회차와 초안 공연의 회차는 404다. */
    @Transactional(readOnly = true)
    public PerformanceLayout getPerformanceLayout(Long performanceId) {
        Long layoutId = seatQueryMetrics.measure("performance", () -> performanceRepository.findPublishedLayoutId(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다.")));
        List<SeatPosition> seats = seatQueryMetrics.measure("layout_seats",
                () -> layoutSeatPositions.findByLayoutId(layoutId));
        return new PerformanceLayout(layoutId, seats);
    }

    /** 구매 가능 정보 조회에는 좌석 배치 없이 회차당 한도만 제공한다. */
    @Transactional(readOnly = true)
    public int getMaxTicketsPerUser(Long performanceId) {
        return performanceRepository.findPublishedMaxTicketsPerUser(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 선점에 필요한 회차 정책과 판매 가능한 배치 좌석만 제공한다. */
    @Transactional(readOnly = true)
    public BookingPerformance getBookingPerformance(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다."));
        if (!concertRepository.isPerformancePublished(performanceId).orElse(false)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return bookingPerformance(performance);
    }

    /** 선점 트랜잭션이 끝날 때까지 회차 설정 변경을 막는다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public BookingPerformance getBookingPerformanceForReservation(Long performanceId) {
        Performance performance = performanceRepository.findSharedById(performanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다."));
        if (!concertRepository.isPerformancePublished(performanceId).orElse(false)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return bookingPerformance(performance);
    }

    private BookingPerformance bookingPerformance(Performance performance) {
        Set<Long> sellableSeatIds = layoutSeatRepository.findAllById_LayoutId(performance.getLayoutId()).stream()
                .filter(LayoutSeat::isSellable)
                .map(seat -> seat.getId().getVenueSeatId())
                .collect(Collectors.toUnmodifiableSet());
        return new BookingPerformance(performance.getLayoutId(), performance.getBookingOpensAt(),
                performance.getBookingClosesAt(), performance.getMaxTicketsPerUser(), sellableSeatIds);
    }
}
