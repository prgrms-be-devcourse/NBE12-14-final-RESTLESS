package com.restless.fanmeeting.api;

import com.restless.fanmeeting.api.dto.FanMeetingSummaryResponse;
import com.restless.fanmeeting.application.FanMeetingSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 API. `/api/admin/**`는 보안 설정이 ADMIN만 통과시킨다(policy.md 6절). 여기서 역할을 다시 검사하지 않는다. */
@RestController
@RequestMapping("/api/admin/fan-meetings")
@RequiredArgsConstructor
public class AdminFanMeetingController {

    private final FanMeetingSummaryService summaryService;

    @GetMapping("/{eventId}/summary")
    public FanMeetingSummaryResponse summary(@PathVariable Long eventId) {
        return FanMeetingSummaryResponse.from(summaryService.summary(eventId));
    }
}
