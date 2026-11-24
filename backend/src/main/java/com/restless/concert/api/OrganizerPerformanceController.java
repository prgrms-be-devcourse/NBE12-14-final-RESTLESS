package com.restless.concert.api;

import com.restless.auth.LoginMember;
import com.restless.concert.application.OrganizerPerformanceService;
import com.restless.concert.application.dto.PerformanceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/performances")
@RequiredArgsConstructor
public class OrganizerPerformanceController {
    private final OrganizerPerformanceService performances;

    @PutMapping("/{performanceId}")
    public PerformanceInfo update(@LoginMember Long userId, @PathVariable Long performanceId,
                                  @Valid @RequestBody UpdatePerformanceRequest request) {
        return performances.update(userId, performanceId, request.startsAt(), request.bookingOpensAt(),
                request.bookingClosesAt(), request.maxTicketsPerUser());
    }

    public record UpdatePerformanceRequest(@NotNull Instant startsAt, @NotNull Instant bookingOpensAt,
                                           @NotNull Instant bookingClosesAt,
                                           @NotNull @Positive Integer maxTicketsPerUser) { }
}
