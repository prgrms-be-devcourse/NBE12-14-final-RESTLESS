package com.restless.fanmeeting.domain;

/** 행사 상태 (state-machines.md 1절). */
public enum FanMeetingStatus {

    SCHEDULED,

    OPEN,

    CLOSED,

    DRAWING,

    PAYMENT_IN_PROGRESS,

    RECRUITMENT_COMPLETED
}
