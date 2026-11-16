package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingOrder;

/** 내 주문과 지금 포기할 수 있는지. 포기 가능 여부는 조회 시각 기준이다. */
public record MyFanMeetingOrder(FanMeetingOrder order, boolean declinable) {
}
