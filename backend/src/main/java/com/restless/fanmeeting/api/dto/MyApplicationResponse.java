package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.domain.FanMeetingApplication;
import java.time.Instant;

/** 내 응모 상태. status는 APPLIED, WITHDRAWN, 응모한 적 없으면 NONE(appliedAt null). */
public record MyApplicationResponse(String status, Instant appliedAt) {

    static final String NONE = "NONE";

    public static MyApplicationResponse from(FanMeetingApplication application) {
        return new MyApplicationResponse(application.getStatus().name(), application.getAppliedAt());
    }

    public static MyApplicationResponse none() {
        return new MyApplicationResponse(NONE, null);
    }
}
