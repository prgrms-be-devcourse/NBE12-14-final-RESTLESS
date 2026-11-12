package com.restless.member.domain;

/**
 * 회원 역할. 가입하면 USER다. ORGANIZER(주최자)와 ADMIN은 DB에서 직접 지정한다.
 * 주최자가 쓸 수 있는 API 범위는 아직 정하지 않았다 (docs/product/open-questions.md).
 */
public enum MemberRole {
    USER,
    ORGANIZER,
    ADMIN
}
