package com.restless.ticketing.application;

import com.restless.concert.application.OrganizerPerformanceService;
import com.restless.concert.application.dto.PerformanceInfo;
import com.restless.concert.application.dto.SeatSetupLayout;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrganizerPerformanceQueryService {
    private final OrganizerPerformanceService performances;
    private final TicketSeatRepository seats;

    @Transactional(readOnly = true)
    public List<SetupStatus> list(Long actorId, Long concertId) {
        var setups = performances.listOwnedSetup(actorId, concertId);
        if (setups.isEmpty()) return List.of();
        var ids = setups.stream().map(s -> s.performance().performanceId()).toList();
        Map<Long, List<TicketSeat>> byPerformance = seats.findAllByPerformanceIdIn(ids).stream()
                .collect(Collectors.groupingBy(TicketSeat::getPerformanceId));
        return setups.stream().map(setup -> status(setup, byPerformance.getOrDefault(setup.performance().performanceId(), List.of())))
                .toList();
    }

    private SetupStatus status(OrganizerPerformanceService.PerformanceSetup setup, List<TicketSeat> actual) {
        var expected = setup.seats();
        if (actual.isEmpty()) return new SetupStatus(setup.performance(), "NOT_CONFIGURED", expected.size(), 0, List.of());
        Map<Long, TicketSeat> byVenueSeat = actual.stream().collect(Collectors.toMap(TicketSeat::getVenueSeatId, Function.identity()));
        boolean matches = !expected.isEmpty() && expected.size() == actual.size() && expected.stream().allMatch(seat -> {
            TicketSeat current = byVenueSeat.get(seat.venueSeatId());
            return current != null && Objects.equals(current.getLayoutId(), setup.performance().layoutId());
        });
        if (!matches) return new SetupStatus(setup.performance(), "INCONSISTENT", expected.size(), actual.size(), List.of());
        var bySection = expected.stream().collect(Collectors.groupingBy(SeatSetupLayout.Seat::sectionLabel));
        var sections = bySection.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var first = byVenueSeat.get(entry.getValue().getFirst().venueSeatId());
            boolean uniform = entry.getValue().stream().map(seat -> byVenueSeat.get(seat.venueSeatId()))
                    .allMatch(seat -> seat.getPrice() == first.getPrice() && seat.getGrade().equals(first.getGrade()));
            return new Section(entry.getKey(), entry.getValue().size(), uniform ? first.getGrade() : null,
                    uniform ? first.getPrice() : null, uniform);
        }).toList();
        String state = sections.stream().allMatch(Section::uniform) ? "CONFIGURED" : "INCONSISTENT";
        return new SetupStatus(setup.performance(), state, expected.size(), actual.size(), sections);
    }

    public record SetupStatus(PerformanceInfo performance, String pricingStatus, int sellableSeatCount,
                              int configuredSeatCount, List<Section> sections) { }
    public record Section(String sectionLabel, int seatCount, String grade, Long price, boolean uniform) { }
}
