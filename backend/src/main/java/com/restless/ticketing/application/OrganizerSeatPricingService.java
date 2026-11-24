package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.dto.SeatSetupLayout;
import com.restless.ticketing.domain.TicketSeatStatus;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Function;
import org.springframework.transaction.annotation.Isolation;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회차별 판매 좌석을 생성하고, 예매 전 구역 단위 가격을 수정한다. */
@Service
@RequiredArgsConstructor
public class OrganizerSeatPricingService {
    private final TicketSeatRepository seats;
    private final com.restless.concert.application.OrganizerPerformanceService performances;
    private final SeatHoldRepository holds;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 회차 생성의 같은 트랜잭션에서 공연 공통 가격으로 판매 좌석을 초기화한다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PricingResult initializeFromCommonPrices(Long performanceId, Long layoutId,
                                                     List<SeatSetupLayout.Seat> seats, List<SectionPrice> sections) {
        SeatSetupLayout layout = new SeatSetupLayout(performanceId, layoutId, null, seats);
        Map<String, SectionPrice> prices = validateSections(layout, sections);
        if (!this.seats.findAllByPerformanceId(performanceId).isEmpty()) throw new BusinessException(ErrorCode.TICKET_PRICING_CONFLICT);
        return applyPricing(layout, prices);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PricingResult configure(Long actorId, Long performanceId, List<SectionPrice> sections) {
        SeatSetupLayout layout = performances.lockOwnedSeatLayout(actorId, performanceId);
        Map<String, SectionPrice> prices = validateSections(layout, sections);
        var existing = seats.findAllByPerformanceId(performanceId);
        if (existing.isEmpty()) return createSeats(layout, prices);
        var locked = existing.stream().map(TicketSeat::getId).sorted()
                .map(id -> seats.findLockedById(id).orElseThrow(() -> new IllegalStateException("판매 좌석이 없습니다."))).toList();
        Map<Long, TicketSeat> bySeat = locked.stream().collect(Collectors.toMap(TicketSeat::getVenueSeatId, Function.identity()));
        boolean unchanged = layout.seats().stream().allMatch(seat -> {
            TicketSeat current = bySeat.get(seat.venueSeatId()); SectionPrice price = prices.get(seat.sectionLabel());
            return current != null && current.getGrade().equals(price.grade()) && current.getPrice() == price.price();
        });
        if (unchanged) return new PricingResult(performanceId, layout.layoutId(), locked.size(), false);
        performances.ensureSeatPricingAllowed(actorId, performanceId);
        Instant now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
        if (!now.isBefore(layout.bookingOpensAt()) || holds.existsByPerformanceId(performanceId)
                || locked.stream().anyMatch(seat -> seat.getStatus() != TicketSeatStatus.AVAILABLE || seat.getCurrentHoldId() != null)) {
            throw new BusinessException(ErrorCode.TICKET_PRICING_CLOSED);
        }
        for (var seat : layout.seats()) {
            SectionPrice price = prices.get(seat.sectionLabel());
            bySeat.get(seat.venueSeatId()).changePricing(price.grade(), price.price());
        }
        return new PricingResult(performanceId, layout.layoutId(), locked.size(), true);
    }


    private PricingResult createSeats(SeatSetupLayout layout, Map<String, SectionPrice> prices) {
        for (var seat : layout.seats()) {
            SectionPrice price = prices.get(seat.sectionLabel());
            seats.save(TicketSeat.createAvailable(layout.performanceId(), layout.layoutId(), seat.venueSeatId(), price.grade(), price.price()));
        }
        return new PricingResult(layout.performanceId(), layout.layoutId(), layout.seats().size(), true);
    }

    private PricingResult applyPricing(SeatSetupLayout layout, Map<String, SectionPrice> prices) {
        Long performanceId = layout.performanceId();
        var expectedSeats = layout.seats().stream().sorted(Comparator.comparing(SeatSetupLayout.Seat::venueSeatId)).toList();
        for (var seat : expectedSeats) {
            SectionPrice price = prices.get(seat.sectionLabel());
            seats.save(TicketSeat.createAvailable(performanceId, layout.layoutId(), seat.venueSeatId(), price.grade(), price.price()));
        }
        return new PricingResult(performanceId, layout.layoutId(), expectedSeats.size(), true);
    }

    private Map<String, SectionPrice> validateSections(SeatSetupLayout layout, List<SectionPrice> sections) {
        if (sections == null || sections.isEmpty() || layout.seats().isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Map<String, SectionPrice> prices = new HashMap<>();
        for (SectionPrice section : sections) {
            if (section == null || section.sectionLabel() == null || section.sectionLabel().isBlank()
                    || section.sectionLabel().length() > 50 || section.grade() == null || section.grade().isBlank()
                    || section.grade().length() > 20 || section.price() == null || section.price() <= 0
                    || prices.putIfAbsent(section.sectionLabel(), section) != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        Set<String> expected = layout.seats().stream().map(SeatSetupLayout.Seat::sectionLabel).collect(Collectors.toSet());
        if (!prices.keySet().equals(expected)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 가능한 모든 구역의 가격을 정확히 한 번씩 입력해 주세요.");
        return prices;
    }

    public record SectionPrice(String sectionLabel, String grade, Long price) { }
    public record PricingResult(Long performanceId, Long layoutId, int seatCount, boolean changed) { }
}
