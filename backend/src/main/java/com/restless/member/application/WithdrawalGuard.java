package com.restless.member.application;

/**
 * 탈퇴해도 되는지 도메인별로 확인한다. 진행 중인 거래가 있는 회원의 탈퇴를 막는다.
 *
 * <p>도메인은 이 인터페이스를 구현한 {@code @Component}를 자기 패키지에 두면 된다.
 * 탈퇴할 때 등록된 Guard를 모두 부르고, 하나라도 true면 409 {@code MEMBER_HAS_ACTIVE_PARTICIPATION}으로 막는다.
 * 등록된 Guard가 없으면 누구나 탈퇴할 수 있다.
 *
 * <p>예) 팬미팅: 결제 대기·검토·환불 대기 주문이 있거나, 결제 완료인데 행사가 아직 안 지났으면 true
 * (docs/domains/fanmeeting/policy.md 5절).
 */
public interface WithdrawalGuard {

    /** 이 회원에게 탈퇴를 막아야 할 진행 중인 거래가 있으면 true. */
    boolean hasActiveParticipation(Long userId);
}
