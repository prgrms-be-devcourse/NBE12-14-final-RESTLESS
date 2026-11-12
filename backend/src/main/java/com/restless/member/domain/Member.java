package com.restless.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원. DB 테이블 이름은 users다. 프로필 수정, 탈퇴는 회원 기능 작업에서 추가한다. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    @Column(name = "default_recipient")
    private String defaultRecipient;

    @Column(name = "default_phone")
    private String defaultPhone;

    @Column(name = "default_address")
    private String defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 탈퇴 시각. status가 WITHDRAWN일 때만 값이 있다(DB CHECK ck_users_deleted_at). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Member(String email, String passwordHash, String nickname, MemberRole role, Instant now) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Member register(String email, String passwordHash, String nickname, Instant now) {
        return new Member(email, passwordHash, nickname, MemberRole.USER, now);
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }
}
