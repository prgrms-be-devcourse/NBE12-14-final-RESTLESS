package com.restless.fanmeeting.domain;

/** 선발 상태 (state-machines.md 3절). 추첨 때 당첨은 PAYMENT_PENDING, 예비는 WAITING으로 만들어진다. */
public enum FanMeetingSelectionStatus {

    PAYMENT_PENDING,

    WAITING
}
