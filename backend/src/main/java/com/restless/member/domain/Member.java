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

/** 회원. DB 테이블 이름은 users다. 탈퇴하면 행은 남기고 status와 deleted_at만 바꾼다. */
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

    /**
     * 프로필을 바꾼다. null인 값은 그대로 두고, 기본 배송지에 빈 문자열이 오면 그 값을 지운다.
     */
    public void updateProfile(String nickname, String defaultRecipient, String defaultPhone, String defaultAddress,
                              Instant now) {
        if (nickname != null) {
            this.nickname = nickname.strip();
        }
        this.defaultRecipient = merge(this.defaultRecipient, defaultRecipient);
        this.defaultPhone = merge(this.defaultPhone, defaultPhone);
        this.defaultAddress = merge(this.defaultAddress, defaultAddress);
        this.updatedAt = now;
    }

    /** 탈퇴. 행은 지우지 않는다. status와 deleted_at은 DB CHECK(ck_users_deleted_at)로 함께 맞춘다. */
    public void withdraw(Instant now) {
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    private static String merge(String current, String requested) {
        if (requested == null) {
            return current;
        }
        String value = requested.strip();
        return value.isEmpty() ? null : value;
    }
}
