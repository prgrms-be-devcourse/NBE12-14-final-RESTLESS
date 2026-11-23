package com.restless.fanmeeting.api;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.api.dto.FanMeetingDetailResponse;
import com.restless.fanmeeting.application.FanMeetingEventService;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공개 행사 목록·상세 조회. */
@RestController
@RequestMapping("/api/fan-meetings")
@RequiredArgsConstructor
public class FanMeetingController {

    private final FanMeetingEventService eventService;

    @GetMapping
    public FanMeetingPage list(@RequestParam(required = false) FanMeetingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var result = eventService.list(status, page, size);
        return new FanMeetingPage(result.map(FanMeetingSummary::from).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public record FanMeetingPage(List<FanMeetingSummary> items, int page, int size,
                                 long totalElements, int totalPages) {
    }

    public record FanMeetingSummary(Long eventId, String title,
            FanMeetingStatus status, Instant entryOpensAt,
            Instant entryClosesAt, Instant meetingAt, int winnerLimit, long feeAmount) {
        static FanMeetingSummary from(FanMeetingEvent event) {
            return new FanMeetingSummary(event.getId(), event.getTitle(), event.getStatus(),
                    event.publicEntryOpensAt(), event.publicEntryClosesAt(), event.getMeetingAt(),
                    event.getWinnerLimit(), event.getFeeAmount());
        }
    }

    @GetMapping("/{eventId}")
    public FanMeetingDetailResponse detail(@PathVariable Long eventId) {
        return FanMeetingDetailResponse.from(eventService.get(eventId));
    }
}
