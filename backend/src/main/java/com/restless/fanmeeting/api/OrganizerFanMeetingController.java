package com.restless.fanmeeting.api;

import com.restless.auth.LoginMember;
import com.restless.fanmeeting.api.dto.FanMeetingCreateRequest;
import com.restless.fanmeeting.api.dto.FanMeetingCreateResponse;
import com.restless.fanmeeting.api.dto.FanMeetingDetailResponse;
import com.restless.fanmeeting.application.FanMeetingEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 주최자·관리자용 행사 API. 역할은 SecurityConfig(/api/organizer/**)가 확인한다. */
@RestController
@RequestMapping("/api/organizer/fan-meetings")
@RequiredArgsConstructor
public class OrganizerFanMeetingController {

    private final FanMeetingEventService eventService;

    @PutMapping("/{eventId}")
    public FanMeetingDetailResponse update(
            @LoginMember Long userId, @PathVariable Long eventId,
            @Valid @RequestBody FanMeetingCreateRequest request) {
        return FanMeetingDetailResponse.from(
                eventService.update(userId, eventId, request.toSettings()));
    }

    @PostMapping("/{eventId}/cancel")
    public void cancel(@LoginMember Long userId, @PathVariable Long eventId) {
        eventService.cancel(userId, eventId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FanMeetingCreateResponse create(@LoginMember Long userId,
                                           @Valid @RequestBody FanMeetingCreateRequest request) {
        return new FanMeetingCreateResponse(eventService.create(userId, request.toSettings()));
    }
}
