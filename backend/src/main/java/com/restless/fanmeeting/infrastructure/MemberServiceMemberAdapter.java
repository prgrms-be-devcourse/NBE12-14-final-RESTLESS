package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.application.MemberPort;
import com.restless.member.application.MemberService;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** MemberPort 구현. 회원 모듈의 application 서비스로만 묻는다(회원 Repository를 직접 쓰지 않음). */
@Component
@RequiredArgsConstructor
public class MemberServiceMemberAdapter implements MemberPort {

    private final MemberService memberService;

    @Override
    public Instant joinedAt(Long userId) {
        return memberService.joinedAt(userId);
    }

    @Override
    public Set<Long> activeMemberIds(Collection<Long> userIds) {
        return memberService.activeMemberIds(userIds);
    }
}
