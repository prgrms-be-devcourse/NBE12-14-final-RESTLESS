package com.restless.member.domain;

/**
 * 회원 상태. 다른 도메인은 탈퇴 여부를 이 값으로 판단한다.
 * 값을 추가하면 users 테이블의 CHECK 제약도 새 마이그레이션으로 바꾼다.
 */
public enum MemberStatus {
    ACTIVE,
    WITHDRAWN
}
