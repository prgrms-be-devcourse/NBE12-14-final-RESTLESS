package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.dto.PerformanceInfo;
import com.restless.concert.application.dto.SeatSetupLayout;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.infrastructure.ConcertRepository;
import com.restless.concert.infrastructure.PerformanceRepository;
import com.restless.concert.infrastructure.ConcertSectionPriceRepository;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.VenueLayoutRepository;
import com.restless.concert.infrastructure.VenueRepository;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.restless.concert.domain.LayoutSeat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;


/** 공연 소유자만 같은 공연장의 등록된 배치로 회차를 만든다. */
@Service
@RequiredArgsConstructor
public class OrganizerPerformanceService {
    private final ConcertRepository concerts;
    private final PerformanceRepository performances;
    private final ConcertSectionPriceRepository sectionPrices;
    private final VenueRepository venues;
    private final VenueLayoutRepository layouts;
    private final LayoutSeatRepository layoutSeats;
    private final MemberService members;
    private final ReservationHistory history;
    private final JdbcTemplate jdbc;
    private final PerformanceSeatCreator seatCreator;

    @Transactional(readOnly = true)
    public List<PerformanceSetup> listOwnedSetup(Long actorId, Long concertId) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        concerts.findById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var items = performances.findAllByConcertIdOrderByStartsAtAsc(concertId);
        if (items.isEmpty()) return List.of();
        var layoutIds = items.stream().map(Performance::getLayoutId).distinct().toList();
        var layoutById = layoutSeats.findAllById_LayoutIdIn(layoutIds).stream().filter(LayoutSeat::isSellable)
                .collect(java.util.stream.Collectors.groupingBy(seat -> seat.getId().getLayoutId(),
                        java.util.stream.Collectors.mapping(seat -> new SeatSetupLayout.Seat(seat.getId().getVenueSeatId(), seat.getSectionLabel()), java.util.stream.Collectors.toList())));
        return items.stream().map(p -> new PerformanceSetup(new PerformanceInfo(p.getId(), p.getConcertId(), p.getVenueId(),
                p.getLayoutId(), p.getStartsAt(), p.getBookingOpensAt(), p.getBookingClosesAt(), p.getMaxTicketsPerUser(), p.getStatus()),
                List.copyOf(layoutById.getOrDefault(p.getLayoutId(), List.of())))).toList();
    }

    public record PerformanceSetup(PerformanceInfo performance, List<SeatSetupLayout.Seat> seats) { }

    public record BatchPerformance(Instant startsAt, Instant bookingClosesAt) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long actorId, Long performanceId) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (performanceId == null || performanceId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Long concertId = performances.findConcertId(performanceId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Concert concert = concerts.findLockedById(concertId)
                .filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Performance performance = performances.findLockedById(performanceId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (concert.isPublished() || concert.getPublishedAt() != null || history.hasSeatHold(performanceId))
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        history.deleteUnreservedSeats(performanceId);
        try {
            performances.delete(performance);
            performances.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<PerformanceInfo> createBatch(Long actorId, Long concertId, Instant opensAt, Integer maximum,
                                             List<BatchPerformance> items) {
        if (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "회차는 1개 이상 10개 이하로 등록해야 합니다.");
        }
        var result = new ArrayList<PerformanceInfo>(items.size());
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            try {
                result.add(create(actorId, concertId, item.startsAt(), opensAt, item.bookingClosesAt(), maximum));
            } catch (BusinessException ex) {
                if (ex.getErrorCode() != ErrorCode.INVALID_REQUEST) throw ex;
                throw new BusinessException(ex.getErrorCode(), ex.getMessage(), Map.of("performanceIndex", i));
            }
        }
        return List.copyOf(result);
    }

    public List<SeatSetupLayout.Seat> sellableSeatsForLayout(Long layoutId) {
        return layoutSeats.findAllById_LayoutId(layoutId).stream().filter(LayoutSeat::isSellable)
                .map(seat -> new SeatSetupLayout.Seat(seat.getId().getVenueSeatId(), seat.getSectionLabel())).toList();
    }

    /** 잠금 이후 기존 회차를 최신 커밋 기준으로 읽도록 READ_COMMITTED를 사용한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PerformanceInfo create(Long actorId, Long concertId, Instant startsAt,
                                  Instant bookingOpensAt, Instant bookingClosesAt, Integer maxTicketsPerUser) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (concertId == null || concertId <= 0
                || startsAt == null || bookingOpensAt == null || bookingClosesAt == null
                || maxTicketsPerUser == null || maxTicketsPerUser <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Concert concert = concerts.findLockedById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (concert.isPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연에는 회차를 추가할 수 없습니다.");
        if (concert.getLayoutId() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연의 공통 배치를 먼저 선택해야 합니다.");
        var configuredPrices = sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId);
        if (configuredPrices.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공통 구역별 가격을 먼저 설정해야 합니다.");
        if (concert.getPeriodStartDate() == null || concert.getPeriodEndDate() == null || concert.getDurationMinutes() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 기간을 먼저 설정해야 합니다.");
        }
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        var layout = layouts.findById(concert.getLayoutId())
                .filter(candidate -> candidate.getVenueId().equals(concert.getVenueId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연장의 배치를 찾을 수 없습니다."));
        LocalDate date = startsAt.atZone(ZoneId.of(venue.getTimezone())).toLocalDate();
        if (date.isBefore(concert.getPeriodStartDate()) || date.isAfter(concert.getPeriodEndDate())
                || !bookingOpensAt.isBefore(bookingClosesAt) || bookingClosesAt.isAfter(startsAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 기간 또는 예매 시간이 올바르지 않습니다.");
        }
        validateOverlap(concert, startsAt, null);
        Performance performance = performances.save(Performance.create(concertId, venue.getId(), layout.getId(),
                startsAt, bookingOpensAt, bookingClosesAt, maxTicketsPerUser, "SCHEDULED"));
        var sellableSeats = sellableSeatsForLayout(layout.getId());
        var prices = configuredPrices.stream().map(price -> new PerformanceSeatCreator.Price(
                price.getSectionLabel(), price.getGrade(), price.getPrice())).toList();
        seatCreator.create(performance.getId(), layout.getId(), sellableSeats, prices);

        return new PerformanceInfo(performance.getId(), performance.getConcertId(), performance.getVenueId(),
                performance.getLayoutId(), performance.getStartsAt(), performance.getBookingOpensAt(),
                performance.getBookingClosesAt(), performance.getMaxTicketsPerUser(), performance.getStatus());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PerformanceInfo update(Long actorId, Long performanceId, Instant startsAt, Instant opensAt,
                                  Instant closesAt, Integer maximum) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (performanceId == null || performanceId <= 0 || startsAt == null || opensAt == null || closesAt == null
                || maximum == null || maximum <= 0 || !opensAt.isBefore(closesAt) || closesAt.isAfter(startsAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Long concertId = performances.findConcertId(performanceId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Concert concert = concerts.findLockedById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 공개된 공연의 회차는 바뀌지 않으므로 잠그지 않는다. 진행 중인 선점이 거절 응답을 기다리지 않게 한다.
        Performance p = (concert.isPublished() ? performances.findById(performanceId) : performances.findLockedById(performanceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        LocalDate date = startsAt.atZone(ZoneId.of(venue.getTimezone())).toLocalDate();
        if (concert.getPeriodStartDate() == null || concert.getPeriodEndDate() == null
                || date.isBefore(concert.getPeriodStartDate()) || date.isAfter(concert.getPeriodEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "회차가 공연 기간 안에 있어야 합니다.");
        }
        boolean changed = !p.getStartsAt().equals(startsAt) || !p.getBookingOpensAt().equals(opensAt)
                || !p.getBookingClosesAt().equals(closesAt) || p.getMaxTicketsPerUser() != maximum;
        if (changed) {
            if (concert.isPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연의 회차는 변경할 수 없습니다.");
            validateOverlap(concert, startsAt, performanceId);
            Instant now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
            if (!now.isBefore(opensAt) || history.hasSeatHold(performanceId)
                    || (!now.isBefore(p.getBookingOpensAt()) && p.getBookingOpensAt().equals(opensAt))) {
                throw new BusinessException(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED);
            }
            p.changeSchedule(startsAt, opensAt, closesAt, maximum);
        }
        return new PerformanceInfo(p.getId(), p.getConcertId(), p.getVenueId(), p.getLayoutId(), p.getStartsAt(),
                p.getBookingOpensAt(), p.getBookingClosesAt(), p.getMaxTicketsPerUser(), p.getStatus());
    }

    private void validateOverlap(Concert concert, Instant startsAt, Long excludedId) {
        if (concert.getDurationMinutes() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 소요 시간을 먼저 설정해야 합니다.");
        }
        long seconds = Math.multiplyExact(concert.getDurationMinutes().longValue(), 60);
        Instant endsAt = startsAt.plusSeconds(seconds);
        boolean overlaps = performances.findAllByConcertIdOrderByStartsAtAsc(concert.getId()).stream()
                .filter(item -> !item.getId().equals(excludedId))
                .anyMatch(item -> startsAt.isBefore(item.getStartsAt().plusSeconds(seconds)) && item.getStartsAt().isBefore(endsAt));
        if (overlaps) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 회차 시간이 겹칩니다.");
    }
}
