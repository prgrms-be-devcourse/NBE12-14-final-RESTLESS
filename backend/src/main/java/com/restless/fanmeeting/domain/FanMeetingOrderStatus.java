package com.restless.fanmeeting.domain;

/** 팬미팅 결제 기회(주문) 상태 (state-machines.md 4절). 추첨·승격 때 PENDING_PAYMENT로 만들어진다. */
public enum FanMeetingOrderStatus {

    PENDING_PAYMENT,

    /** 결제 결과를 몰라 자리를 지키는 보류. */
    PAYMENT_REVIEW,

    PAID,

    EXPIRED,

    /** 결제 시도 전 포기. */
    CANCELLED,

    /** 늦은 승인·금액 불일치 등으로 승인을 거절함. 결제 모듈이 보상 취소(환불)를 진행 중. */
    REFUND_PENDING,

    /** 보상 취소(환불)가 끝남. */
    REFUNDED
}
