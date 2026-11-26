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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/organizer/performances")
@RequiredArgsConstructor
public class OrganizerPerformanceController {
    private final OrganizerPerformanceService performances;

    @DeleteMapping("/{performanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginMember Long userId, @PathVariable Long performanceId) {
        performances.delete(userId, performanceId);
    }

    @PutMapping("/{performanceId}")
    public PerformanceInfo update(@LoginMember Long userId, @PathVariable Long performanceId,
                                  @Valid @RequestBody UpdatePerformanceRequest request) {
        return performances.update(userId, performanceId, request.startsAt(), request.bookingClosesAt(),
                request.maxTicketsPerUser());
    }

    /** 예매 오픈은 공연 단위로 바꾼다. 예매 마감을 비우면 회차 시작 전날 17시(공연장 시간대)다. */
    public record UpdatePerformanceRequest(@NotNull Instant startsAt, Instant bookingClosesAt,
                                           @NotNull @Positive Integer maxTicketsPerUser) { }
}
