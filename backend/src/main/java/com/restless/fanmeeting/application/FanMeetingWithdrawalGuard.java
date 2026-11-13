package com.restless.fanmeeting.application;

import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.member.application.WithdrawalGuard;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팬미팅 탈퇴 확인 (policy.md 5절). 회원 탈퇴 때 회원 모듈이 부른다.
 * 결제 대기·보류·환불 대기 주문이 있거나, 결제 완료인데 팬미팅이 아직 안 지났으면 탈퇴를 막는다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingWithdrawalGuard implements WithdrawalGuard {

    private final FanMeetingOrderRepository orders;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveParticipation(Long userId) {
        return orders.existsActiveParticipation(userId, clock.instant());
    }
}
