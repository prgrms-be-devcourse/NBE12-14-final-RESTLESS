package com.restless.member.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.domain.Member;
import com.restless.member.infrastructure.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 기능. 가입·조회는 인증이 쓰고, 프로필 수정과 탈퇴는 회원 API가 쓴다. */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<WithdrawalGuard> withdrawalGuards;
    private final ApplicationEventPublisher events;

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

    /**
     * 이 중 탈퇴하지 않은 회원 ID. 다른 도메인이 탈퇴 회원을 빼고 처리할 때 쓴다 (예: 팬미팅 추첨).
     * 없는 ID는 결과에 없다.
     */
    @Transactional(readOnly = true)
    public Set<Long> activeMemberIds(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return memberRepository.findAllById(memberIds).stream()
                .filter(Member::isActive)
                .map(Member::getId)
                .collect(Collectors.toSet());
    }

    /** 가입 시각. 탈퇴 회원도 돌려준다. 없는 회원이면 MEMBER_NOT_FOUND. */
    @Transactional(readOnly = true)
    public Instant joinedAt(Long memberId) {
        return memberRepository.findById(memberId)
                .map(Member::getCreatedAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Member getActive(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /** 프로필 수정. 탈퇴한 회원이면 404 MEMBER_NOT_FOUND. */
    @Transactional
    public Member updateProfile(Long memberId, String nickname, String defaultRecipient, String defaultPhone,
                                String defaultAddress) {
        Member member = getActive(memberId);
        member.updateProfile(nickname, defaultRecipient, defaultPhone, defaultAddress, clock.instant());
        return member;
    }

    /**
     * 탈퇴. 비밀번호를 다시 확인하고, 도메인 Guard가 하나라도 막으면 409로 거절한다.
     * 행은 지우지 않으므로 다른 도메인의 기록(주문, 결제, 응모)은 그대로 남는다.
     * 같은 이메일로는 다시 가입할 수 없다(uk_users_email).
     */
    @Transactional
    public void withdraw(Long memberId, String rawPassword) {
        Member member = getActive(memberId);
        if (!passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
            throw new BusinessException(ErrorCode.MEMBER_PASSWORD_MISMATCH);
        }
        boolean blocked = withdrawalGuards.orderedStream().anyMatch(guard -> guard.hasActiveParticipation(memberId));
        if (blocked) {
            throw new BusinessException(ErrorCode.MEMBER_HAS_ACTIVE_PARTICIPATION);
        }
        member.withdraw(clock.instant());
        events.publishEvent(new MemberWithdrawnEvent(memberId));
    }
}
