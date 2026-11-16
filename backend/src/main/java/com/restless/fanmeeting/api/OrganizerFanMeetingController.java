package com.restless.fanmeeting.api;

import com.restless.auth.LoginMember;
import com.restless.fanmeeting.api.dto.FanMeetingCreateRequest;
import com.restless.fanmeeting.api.dto.FanMeetingCreateResponse;
import com.restless.fanmeeting.application.FanMeetingEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FanMeetingCreateResponse create(@LoginMember Long userId,
                                           @Valid @RequestBody FanMeetingCreateRequest request) {
        return new FanMeetingCreateResponse(eventService.create(userId, request.toSettings()));
    }
}
