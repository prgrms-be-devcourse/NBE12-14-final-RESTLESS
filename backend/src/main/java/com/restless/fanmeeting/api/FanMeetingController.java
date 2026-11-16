package com.restless.fanmeeting.api;

import com.restless.fanmeeting.api.dto.FanMeetingDetailResponse;
import com.restless.fanmeeting.application.FanMeetingEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 행사 상세 조회(공개, SecurityConfig.PUBLIC_GET_PATHS). */
@RestController
@RequestMapping("/api/fan-meetings")
@RequiredArgsConstructor
public class FanMeetingController {

    private final FanMeetingEventService eventService;

    @GetMapping("/{eventId}")
    public FanMeetingDetailResponse detail(@PathVariable Long eventId) {
        return FanMeetingDetailResponse.from(eventService.get(eventId));
    }
}
