package com.restless.member.api;

import com.restless.auth.LoginMember;
import com.restless.member.api.dto.ProfileResponse;
import com.restless.member.api.dto.ProfileUpdateRequest;
import com.restless.member.api.dto.WithdrawRequest;
import com.restless.member.application.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 회원 본인의 프로필과 탈퇴. 탈퇴한 회원은 404 MEMBER_NOT_FOUND. */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/profile")
    public ProfileResponse getProfile(@LoginMember Long userId) {
        return ProfileResponse.from(memberService.getActive(userId));
    }

    @PatchMapping("/profile")
    public ProfileResponse updateProfile(@LoginMember Long userId, @Valid @RequestBody ProfileUpdateRequest request) {
        return ProfileResponse.from(memberService.updateProfile(userId, request.nickname(),
                request.defaultRecipient(), request.defaultPhone(), request.defaultAddress()));
    }

    /** 탈퇴. 성공하면 204이고, 모든 기기의 Refresh Token이 폐기된다. */
    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@LoginMember Long userId, @Valid @RequestBody WithdrawRequest request) {
        memberService.withdraw(userId, request.password());
    }
}
