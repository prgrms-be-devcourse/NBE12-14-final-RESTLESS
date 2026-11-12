package com.restless.auth.application;

import com.restless.member.application.MemberWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴하면 그 회원의 Refresh Token을 모든 기기에서 폐기한다.
 * 탈퇴와 같은 트랜잭션에서 동기로 돈다. 폐기에 실패하면 탈퇴도 롤백된다.
 * Access Token은 만료(최대 1시간)까지 남지만, 회원 API는 탈퇴 회원에게 404를 준다.
 */
@Component
@RequiredArgsConstructor
class MemberWithdrawnListener {

    private final RefreshTokenService refreshTokenService;

    @EventListener
    public void on(MemberWithdrawnEvent event) {
        refreshTokenService.revokeAll(event.userId());
    }
}
