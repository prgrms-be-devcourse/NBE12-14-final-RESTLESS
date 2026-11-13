package com.restless.fanmeeting.domain;

/** 선발 상태 (state-machines.md 3절). 추첨 때 당첨은 PAYMENT_PENDING, 예비는 WAITING으로 만들어진다. */
public enum FanMeetingSelectionStatus {

    PAYMENT_PENDING,

    WAITING,

    /** 기한 안 결제 완료. */
    CONFIRMED,

    /** 기한이 지났거나 승인을 받아들이지 못해 자리를 잃음. */
    EXPIRED
}
