package com.restless.member.domain;

/** 회원 역할. 가입하면 USER다. ADMIN은 DB에서 직접 지정한다. */
public enum MemberRole {
    USER,
    ADMIN
}
