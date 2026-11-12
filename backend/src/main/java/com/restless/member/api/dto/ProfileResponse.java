package com.restless.member.api.dto;

import com.restless.member.domain.Member;

/** 내 프로필. 기본 배송지 값은 등록하지 않았으면 null이다. */
public record ProfileResponse(
        Long memberId,
        String email,
        String nickname,
        String role,
        String defaultRecipient,
        String defaultPhone,
        String defaultAddress) {

    public static ProfileResponse from(Member member) {
        return new ProfileResponse(member.getId(), member.getEmail(), member.getNickname(), member.getRole().name(),
                member.getDefaultRecipient(), member.getDefaultPhone(), member.getDefaultAddress());
    }
}
