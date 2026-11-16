package com.restless.fanmeeting.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** 팬미팅이 회원 모듈에 묻는 것 (ports.md 1절). 회원 테이블을 직접 읽지 않는다. */
public interface MemberPort {

    /** 가입 시각(users.created_at). 조회에 실패하면 예외. */
    Instant joinedAt(Long userId);

    /** 이 중 탈퇴하지 않은 회원 ID. */
    Set<Long> activeMemberIds(Collection<Long> userIds);
}
