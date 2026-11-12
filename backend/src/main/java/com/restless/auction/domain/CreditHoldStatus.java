package com.restless.auction.domain;

/** 입찰 하나에 묶인 크레딧의 상태. RELEASED, CAPTURED는 끝 상태다. */
public enum CreditHoldStatus {
    /** 최고 입찰이라 묶여 있음 */
    HELD,
    /** 추월당해 해제됨 */
    RELEASED,
    /** 낙찰돼 보유액에서 차감됨 */
    CAPTURED
}
