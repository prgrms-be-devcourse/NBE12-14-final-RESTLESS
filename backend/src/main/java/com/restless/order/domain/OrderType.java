package com.restless.order.domain;

/** 공통 주문을 만든 도메인. 팬미팅은 자체 {@code fan_meeting_orders}를 쓰므로 없다 (ADR-0003). */
public enum OrderType {
    DROP, TICKET
}
