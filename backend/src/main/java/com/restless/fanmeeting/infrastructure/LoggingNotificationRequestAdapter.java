package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.application.NotificationRequest;
import com.restless.fanmeeting.application.NotificationRequestPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 알림 요청을 구조화 로그로 남긴다. 회원 정보는 넣지 않는다. */
@Component
public class LoggingNotificationRequestAdapter implements NotificationRequestPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationRequestAdapter.class);

    @Override
    public void request(NotificationRequest request) {
        log.info("FANMEETING_NOTIFICATION type={} eventId={} orderId={} paymentDeadline={}",
                request.type(), request.eventId(), request.orderId(), request.paymentDeadline());
    }
}
