# FanMeeting 데이터 모델

## 1. 전제

확정:

- MySQL 8, PK는 `BIGINT AUTO_INCREMENT` ([ADR-0002](../../decisions/0002-common-conventions.md)).
- 회원 참조는 `user_id`(= `users.id`, BIGINT). 결제 참조는 `payment_id`(= `payments.id`, BIGINT).
- 팬미팅 테이블끼리는 물리 FK(복합 FK 포함). `users`, `payments`로는 물리 FK 없이 ID만 저장한다(논리 참조, 모듈 경계).

**[팀 미확정]** `attempt_id` 타입(결제 모듈을 따름, `VARCHAR(64)` 가정).

공통:

- 시각은 UTC `DATETIME(6)`이고 앱 `Clock`이 넣는다(시각 컬럼에 DB 기본값 없음).
- 금액은 `BIGINT`(KRW 원).
- 문자셋은 팀 기본(`utf8mb4`).
- 회원 테이블로 가는 FK는 두지 않는다(모듈 경계).
- `ddl-auto: validate`.
- 팬미팅은 공통 `ORDERS`가 아니라 자기 `fan_meeting_orders`를 소유한다. 공통 `payments.id`는 `payment_id`로만 참조한다.

## 2. DDL (Flyway)

> **실제 스키마의 정본은 마이그레이션 파일**(`backend/src/main/resources/db/migration/V20261111_1020__fanmeeting_create_tables.sql`)이다. 아래는 설계 의도를 보여 주는 DDL이고, 머지된 파일과 다음이 다르다.
>
> - **조회용 인덱스 10개(`KEY idx_...`)가 머지된 파일에 아직 없다.** 다음 마이그레이션(`V<날짜>_<시각>__fanmeeting_add_indexes.sql`)으로 추가한다. Worker 조회와 승격·만료의 `FOR UPDATE`가 인덱스 없이 돌면 훑은 행을 전부 잠그므로, 승격·만료 구현 전에 넣는다.
> - 제약 이름 규칙(`pk_`/`uk_`/`fk_`/`ck_` + 테이블 이름), 일부 길이(`algorithm_version` 20, `last_error` 500, `approved_attempt_id` 100, selections `status` 20)는 머지된 파일을 따른다.

```sql
CREATE TABLE fan_meeting_events (
  event_id                  BIGINT       NOT NULL AUTO_INCREMENT,
  title                     VARCHAR(200) NOT NULL,
  status                    VARCHAR(30)  NOT NULL,
  entry_opens_at            DATETIME(6)  NOT NULL,
  entry_closes_at           DATETIME(6)  NOT NULL,
  first_notice_scheduled_at DATETIME(6)  NOT NULL,
  winner_limit              INT          NOT NULL,
  reserve_limit             INT          NOT NULL,
  fee_amount                BIGINT       NOT NULL,
  meeting_at                DATETIME(6)  NOT NULL,
  promotion_closes_at       DATETIME(6)  NOT NULL,
  closed_at                 DATETIME(6)  NULL,
  created_at                DATETIME(6)  NOT NULL,
  updated_at                DATETIME(6)  NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_events_status_opens (status, entry_opens_at),
  KEY idx_events_status_closes (status, entry_closes_at),
  CONSTRAINT chk_events_limits CHECK (winner_limit >= 1 AND reserve_limit >= 0 AND fee_amount >= 0),
  CONSTRAINT chk_events_entry CHECK (entry_closes_at > entry_opens_at)
);

CREATE TABLE fan_meeting_applications (
  application_id BIGINT      NOT NULL AUTO_INCREMENT,
  event_id       BIGINT      NOT NULL,
  user_id        BIGINT      NOT NULL,
  status         VARCHAR(20) NOT NULL,   -- APPLIED / WITHDRAWN
  applied_at     DATETIME(6) NOT NULL,   -- 재응모 때 현재 시각으로 갱신
  updated_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (application_id),
  UNIQUE KEY uq_app_event_user (event_id, user_id),
  UNIQUE KEY uq_app_id_event_user (application_id, event_id, user_id),   -- 복합 FK 참조용
  KEY idx_app_event_status (event_id, status, applied_at),
  CONSTRAINT fk_app_event FOREIGN KEY (event_id) REFERENCES fan_meeting_events (event_id)
);

CREATE TABLE fan_meeting_draw_runs (
  draw_run_id       BIGINT        NOT NULL AUTO_INCREMENT,
  event_id          BIGINT        NOT NULL,
  status            VARCHAR(20)   NOT NULL,   -- PENDING / RUNNING / COMPLETED
  random_seed       BINARY(32)    NOT NULL,
  algorithm_version VARCHAR(40)   NOT NULL,   -- HMAC_SHA256_RANK_V1
  candidate_count   INT           NULL,
  lease_token       CHAR(36)      NULL,
  lease_until       DATETIME(6)   NULL,
  attempt_count     INT           NOT NULL DEFAULT 0,
  started_at        DATETIME(6)   NULL,
  completed_at      DATETIME(6)   NULL,
  last_error        VARCHAR(1000) NULL,
  created_at        DATETIME(6)   NOT NULL,
  updated_at        DATETIME(6)   NOT NULL,
  PRIMARY KEY (draw_run_id),
  UNIQUE KEY uq_draw_event (event_id),
  UNIQUE KEY uq_draw_id_event (draw_run_id, event_id),
  KEY idx_draw_status_lease (status, lease_until),
  CONSTRAINT fk_draw_event FOREIGN KEY (event_id) REFERENCES fan_meeting_events (event_id)
);

CREATE TABLE fan_meeting_selections (
  selection_id   BIGINT      NOT NULL AUTO_INCREMENT,
  event_id       BIGINT      NOT NULL,
  draw_run_id    BIGINT      NOT NULL,
  application_id BIGINT      NOT NULL,
  user_id        BIGINT      NOT NULL,
  selection_type VARCHAR(20) NOT NULL,   -- WINNER / RESERVE
  selection_rank INT         NOT NULL,
  status         VARCHAR(30) NOT NULL,
  created_at     DATETIME(6) NOT NULL,
  updated_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (selection_id),
  UNIQUE KEY uq_sel_application (application_id),
  UNIQUE KEY uq_sel_rank (draw_run_id, selection_type, selection_rank),
  UNIQUE KEY uq_sel_id_event_user (selection_id, event_id, user_id),     -- 복합 FK 참조용
  KEY idx_sel_event_type_status_rank (event_id, selection_type, status, selection_rank),
  KEY idx_sel_event_status (event_id, status),
  KEY idx_sel_user_status (user_id, status),
  CONSTRAINT fk_sel_draw FOREIGN KEY (draw_run_id, event_id)
    REFERENCES fan_meeting_draw_runs (draw_run_id, event_id),
  CONSTRAINT fk_sel_app FOREIGN KEY (application_id, event_id, user_id)
    REFERENCES fan_meeting_applications (application_id, event_id, user_id),
  CONSTRAINT chk_sel_rank CHECK (selection_rank >= 1)
);

CREATE TABLE fan_meeting_orders (
  order_id             BIGINT      NOT NULL AUTO_INCREMENT,
  event_id             BIGINT      NOT NULL,
  selection_id         BIGINT      NOT NULL,
  user_id              BIGINT      NOT NULL,
  amount               BIGINT      NOT NULL,   -- 생성 시점 fee_amount 스냅샷
  status               VARCHAR(30) NOT NULL,
  issued_at            DATETIME(6) NOT NULL,   -- 결제 기한의 시작
  payment_id           BIGINT      NULL,       -- 공통 payments.id. 포기 판정에 쓰지 않음
  attempt_requested_at DATETIME(6) NULL,       -- 결제 시작 요청 표시. 한 번 기록하면 지우지 않음. 포기 판정 기준
  approved_attempt_id  VARCHAR(64) NULL,       -- 이 주문에 처음 반영한 승인 시도
  paid_at              DATETIME(6) NULL,       -- 유효 승인 시각. 늦은 승인은 채우지 않음
  review_started_at    DATETIME(6) NULL,       -- 보류로 바뀐 시각
  review_warned_at     DATETIME(6) NULL,       -- 30분 경고를 남긴 시각
  created_at           DATETIME(6) NOT NULL,
  updated_at           DATETIME(6) NOT NULL,
  PRIMARY KEY (order_id),
  UNIQUE KEY uq_order_selection (selection_id),
  UNIQUE KEY uq_order_payment (payment_id),
  KEY idx_order_event_status (event_id, status),
  KEY idx_order_status_issued (status, issued_at),
  KEY idx_order_user_status (user_id, status),
  CONSTRAINT fk_order_sel FOREIGN KEY (selection_id, event_id, user_id)
    REFERENCES fan_meeting_selections (selection_id, event_id, user_id),
  CONSTRAINT chk_order_amount CHECK (amount >= 0)
);
```

가짜 결제 테이블 (별도 마이그레이션 폴더 `db/migration-fake`, `local`/`test` 프로필에만 적용. 3절):

```sql
CREATE TABLE fake_payments (
  payment_id   BIGINT      NOT NULL AUTO_INCREMENT,
  payable_type VARCHAR(40) NOT NULL,
  payable_id   BIGINT      NOT NULL,
  created_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (payment_id),
  UNIQUE KEY uq_fake_payable (payable_type, payable_id)
);

CREATE TABLE fake_payment_attempts (
  attempt_id      VARCHAR(64)  NOT NULL,
  payment_id      BIGINT       NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  amount          BIGINT       NOT NULL,
  expires_at      DATETIME(6)  NOT NULL,
  result          VARCHAR(20)  NOT NULL,   -- READY / UNKNOWN / APPROVED / FAILED (P0 가짜는 PROCESSING을 쓰지 않음)
  checkout_url    VARCHAR(500) NOT NULL,
  approved_at     DATETIME(6)  NULL,
  approved_amount BIGINT       NULL,
  created_at      DATETIME(6)  NOT NULL,
  updated_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uq_fake_attempt_key (payment_id, idempotency_key),
  KEY idx_fake_attempt_payment_result (payment_id, result),
  CONSTRAINT fk_fake_attempt_payment FOREIGN KEY (payment_id) REFERENCES fake_payments (payment_id)
);
```

- `event_id`, `user_id`는 Selection·Order에 중복 저장한 값이다. **복합 FK가 부모와 같은 값인지 DB에서 막는다**(INV-FM-14).
- Worker 스캔용 인덱스(`idx_events_status_*`, `idx_draw_status_lease`)는 EXPLAIN으로 확인 후 확정한다.

## 3. 마이그레이션 분리 (가짜 결제 테이블)

| 위치 | 내용 | 적용 프로필 |
|---|---|---|
| `src/main/resources/db/migration/V20261111_1020__fanmeeting_create_tables.sql` (머지됨) | `fan_meeting_*` 5개 테이블 | 모든 프로필 |
| `src/main/resources/db/migration/V<날짜>_<시각>__fanmeeting_add_indexes.sql` (추가 예정) | 조회용 인덱스 10개 | 모든 프로필 |
| `src/main/resources/db/migration-fake/V<날짜>_<시각>__fanmeeting_create_fake_payment_tables.sql` (4일차에 만듦) | `fake_payments`, `fake_payment_attempts` | `local`, `test`만 |

파일 이름은 팀 규칙([CONTRIBUTING.md](../../../CONTRIBUTING.md))을 따른다: `V<날짜>_<시각>__<도메인>_<내용>.sql`. 날짜·시각은 **파일을 만드는 시점**으로 적는다. 팀 설정이 `out-of-order: true`라서, 다른 사람 파일과 머지 순서가 달라도 빠진 파일을 적용한다.

- `application-local.yml`, `application-test.yml`에서만 `spring.flyway.locations: classpath:db/migration,classpath:db/migration-fake`. 그 밖의 프로필은 기본값 `classpath:db/migration`만 쓴다.
- **두 폴더는 버전(날짜·시각) 순서를 하나로 공유한다.** 가짜 테이블 파일도 만드는 시점의 날짜·시각을 쓴다. 미래 날짜를 미리 적지 않는다.
- 운영 프로필은 가짜 폴더를 아예 읽지 않으므로 버전 사이가 비어도 문제없다.
- 한 번 개발 브랜치(`dev`)에 머지된 파일은 고치지 않는다. 바꿀 내용은 새 파일로 추가한다.
- 가짜 Adapter 빈과 가짜 테이블 엔티티는 `@Profile({"local", "test"})`로 감싸고, 엔티티는 별도 패키지에 둔다. 그 외 프로필에서 이 엔티티가 스캔되면 `ddl-auto: validate`가 "테이블 없음"으로 실패하므로 스캔 범위에서도 뺀다.
- 실제 결제 모듈을 연결하면 `db/migration-fake`와 가짜 Adapter를 통째로 지운다. 본 마이그레이션은 바꾸지 않는다.

## 4. 정합성 SQL

팬미팅 테이블끼리는 복합 FK가 DB에서 막는다. 아래 SQL은 관통 시나리오 끝에서 한 번 더 돌려 0건인지 확인한다(C25).

```sql
-- Selection이 다른 행사·다른 회원의 응모를 가리킴
SELECT COUNT(*) FROM fan_meeting_selections s
  JOIN fan_meeting_applications a ON a.application_id = s.application_id
 WHERE a.event_id <> s.event_id OR a.user_id <> s.user_id;
-- Selection이 다른 행사의 추첨을 가리킴
SELECT COUNT(*) FROM fan_meeting_selections s
  JOIN fan_meeting_draw_runs d ON d.draw_run_id = s.draw_run_id
 WHERE d.event_id <> s.event_id;
-- Order가 다른 행사·다른 회원의 선발을 가리킴
SELECT COUNT(*) FROM fan_meeting_orders o
  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
 WHERE s.event_id <> o.event_id OR s.user_id <> o.user_id;
```
