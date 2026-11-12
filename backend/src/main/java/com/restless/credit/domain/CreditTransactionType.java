package com.restless.credit.domain;

/** 크레딧 변동 종류. docs/domains/auction/state-machines.md 5절. */
public enum CreditTransactionType {
    /** 지급: 보유 + */
    GRANT,
    /** 입찰 수락: 묶인 금액 + */
    HOLD,
    /** 추월: 묶인 금액 − */
    RELEASE,
    /** 낙찰: 보유 −, 묶인 금액 − */
    CAPTURE
}
