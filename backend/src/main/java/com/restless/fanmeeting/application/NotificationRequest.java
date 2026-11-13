package com.restless.fanmeeting.application;

import java.time.Instant;

/** 알림 요청 1건 (transactions.md 11절). type은 WINNER(추첨 당첨) 또는 PROMOTION(승격). */
public record NotificationRequest(String type, Long eventId, Long orderId, Instant paymentDeadline) {

    public static NotificationRequest winner(Long eventId, Long orderId, Instant paymentDeadline) {
        return new NotificationRequest("WINNER", eventId, orderId, paymentDeadline);
    }
}
