package com.restless.fanmeeting.api;

import com.restless.auth.LoginMember;
import com.restless.fanmeeting.api.dto.MyApplicationResponse;
import com.restless.fanmeeting.application.FanMeetingApplicationRetrier;
import com.restless.fanmeeting.application.FanMeetingApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 응모, 응모 취소, 내 응모 상태. 회원 ID는 토큰에서만 받는다. */
@RestController
@RequestMapping("/api/fan-meetings/{eventId}/applications")
@RequiredArgsConstructor
public class FanMeetingApplicationController {

    private final FanMeetingApplicationService applicationService;
    private final FanMeetingApplicationRetrier applicationRetrier;

    /** 응모·재응모. 동시 요청 실패는 재시도 빈이 처리한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyApplicationResponse apply(@LoginMember Long userId, @PathVariable Long eventId) {
        return MyApplicationResponse.from(applicationRetrier.apply(userId, eventId));
    }

    @GetMapping("/me")
    public MyApplicationResponse mine(@LoginMember Long userId, @PathVariable Long eventId) {
        return applicationService.findMine(userId, eventId)
                .map(MyApplicationResponse::from)
                .orElseGet(MyApplicationResponse::none);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@LoginMember Long userId, @PathVariable Long eventId) {
        applicationService.cancel(userId, eventId);
    }
}
