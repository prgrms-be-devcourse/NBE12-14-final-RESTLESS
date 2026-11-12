package com.restless.member.application;

/**
 * 회원이 탈퇴했다. 탈퇴 트랜잭션 안에서 동기로 발행된다.
 * 인증은 이 이벤트를 받아 그 회원의 Refresh Token을 모두 폐기한다(member는 auth를 모른다).
 */
public record MemberWithdrawnEvent(Long userId) {
}
