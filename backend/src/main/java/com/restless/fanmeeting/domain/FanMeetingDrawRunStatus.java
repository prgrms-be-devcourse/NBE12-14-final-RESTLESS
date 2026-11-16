package com.restless.fanmeeting.domain;

/** 추첨 실행 상태. PENDING → RUNNING → COMPLETED (state-machines.md 6절). */
public enum FanMeetingDrawRunStatus {

    PENDING,

    RUNNING,

    COMPLETED
}
