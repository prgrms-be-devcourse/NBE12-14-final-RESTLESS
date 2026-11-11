package com.restless.member.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.domain.Member;
import com.restless.member.infrastructure.MemberRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증이 쓰는 회원 기능. 프로필 조회·수정, 탈퇴는 회원 기능 작업에서 추가한다. */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final Clock clock;

    /** 가입. 비밀번호는 이미 해시된 값을 받는다. 같은 이메일이 동시에 가입하면 UNIQUE 제약이 막는다. */
    @Transactional
    public Member register(String email, String passwordHash, String nickname) {
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        try {
            return memberRepository.saveAndFlush(Member.register(email, passwordHash, nickname, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Member> findActiveByEmail(String email) {
        return memberRepository.findByEmail(email).filter(Member::isActive);
    }

    @Transactional(readOnly = true)
    public Member getActive(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
