package com.restless.member.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.domain.Member;
import com.restless.member.infrastructure.MemberRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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

    /**
     * 회원 ID → 닉네임. 다른 도메인이 화면에 이름을 보여 줄 때 쓴다 (예: 경매 최고 입찰자).
     * 없는 ID는 결과에 없다. 탈퇴 회원도 닉네임을 돌려준다.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> nicknames(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getNickname));
    }

    @Transactional(readOnly = true)
    public Member getActive(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
