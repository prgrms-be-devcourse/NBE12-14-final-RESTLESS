package com.restless.fanmeeting.application;

/** 알림 요청 기록. 실제 발송은 하지 않는다(P0). 트랜잭션이 커밋된 뒤에만 부른다. */
public interface NotificationRequestPort {

    void request(NotificationRequest request);
}
