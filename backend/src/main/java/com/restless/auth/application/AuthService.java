package com.restless.auth.application;

import com.restless.auth.application.RefreshTokenService.Rotation;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.auth.infrastructure.JwtTokenProvider.IssuedToken;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.member.domain.Member;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    /** 없는 이메일도 비밀번호 비교 시간을 같게 만들어, 응답 시간으로 가입 여부를 알 수 없게 한다. */
    private final String dummyHash;

    public AuthService(MemberService memberService, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokenService) {
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public Long signup(String email, String rawPassword, String nickname) {
        Member member = memberService.register(normalize(email), passwordEncoder.encode(rawPassword), nickname.strip());
        return member.getId();
    }

    /** 이메일이 없거나, 탈퇴했거나, 비밀번호가 틀리면 모두 같은 오류(INVALID_CREDENTIALS)다. */
    public LoginResult login(String email, String rawPassword) {
        Optional<Member> found = memberService.findActiveByEmail(normalize(email));
        String hash = found.map(Member::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !matches) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        Member member = found.get();
        IssuedToken access = tokenProvider.issue(member.getId(), member.getRole().name());
        String refresh = refreshTokenService.issueNewFamily(member.getId());
        return new LoginResult(access, refresh);
    }

    /**
     * Refresh Token으로 새 Access Token과 새 Refresh Token을 받는다.
     * 그사이 탈퇴한 회원이면 묶음을 폐기하고 거절한다. 역할이 바뀌었으면 새 역할로 발급된다.
     */
    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        Rotation rotation = refreshTokenService.rotate(rawRefreshToken);
        Member member;
        try {
            member = memberService.getActive(rotation.userId());
        } catch (BusinessException e) {
            refreshTokenService.revokeFamilyOf(rotation.refreshToken());
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        IssuedToken access = tokenProvider.issue(member.getId(), member.getRole().name());
        return new LoginResult(access, rotation.refreshToken());
    }

    /** 이 기기의 로그인만 끝낸다. 토큰이 없거나 이미 폐기됐어도 성공으로 본다. */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revoke(rawRefreshToken);
        }
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public record LoginResult(IssuedToken accessToken, String refreshToken) {
    }
}
