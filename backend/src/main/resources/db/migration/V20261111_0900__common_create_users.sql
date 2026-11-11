-- 회원 (공통)
-- 탈퇴 판단은 status(ACTIVE / WITHDRAWN). deleted_at은 탈퇴 시각이며 CHECK로 status와 맞춘다.

CREATE TABLE users
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    nickname          VARCHAR(50)  NOT NULL,
    role              VARCHAR(20)  NOT NULL COMMENT '[확인] 역할 이름 미확정',
    status            VARCHAR(20)  NOT NULL,
    default_recipient VARCHAR(50)  NULL,
    default_phone     VARCHAR(20)  NULL,
    default_address   VARCHAR(255) NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    deleted_at        DATETIME(6)  NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_users_deleted_at CHECK (
        (status = 'WITHDRAWN' AND deleted_at IS NOT NULL)
            OR (status = 'ACTIVE' AND deleted_at IS NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
