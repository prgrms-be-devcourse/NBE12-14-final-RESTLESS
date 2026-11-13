# FanMeeting 트랜잭션

HTTP 응답·오류 코드는 [API 계약](../../contracts/api/fanmeeting.md)에 있다. 상태 전이 판정은 [state-machines.md](state-machines.md)를 따른다.

## 1. 잠금 규칙 (반드시 지킨다)

1. **격리 수준은 `REPEATABLE READ`**(MySQL 기본)다.
2. **항상 Event를 먼저 잠근다.** 그다음 Application → DrawRun → Order → Selection 순서.
3. **Event를 읽고 붙잡기만 하면 `FOR SHARE`:** 응모, 응모 취소, 결제 시작, 결제 결과 반영, 만료, 결제 포기.
4. **Event를 고치거나 "세고 만드는" 일이면 `FOR UPDATE`:** 마감, 추첨 Claim·최종화, 승격(모집 완료 판정 포함).
5. **`FOR SHARE`로 잡은 트랜잭션은 Event 행을 고치지 않는다.** 공유 잠금에서 배타 잠금으로 올리면 서로 교착한다.
6. **Order로 시작하는 내부 처리**(결과 반영, 만료)는 잠그기 전에 `SELECT event_id FROM fan_meeting_orders WHERE order_id = ?`(일반 조회)로 행사를 얻고 2번 순서로 잠근다. `event_id`는 바뀌지 않는 값이다.
7. **잠금을 쥔 채 외부 호출(결제 모듈, Auth 조회)을 하지 않는다.** 외부 호출은 트랜잭션 전이나 커밋 후에 한다.
8. **없는 행을 `FOR UPDATE`로 조회하지 않는다.** RR에서 빈 자리 잠금이 걸려 다른 행의 INSERT까지 막는다(3절).
9. **잠금 시간 초과(1205)·교착(1213)은 트랜잭션 전체를 롤백한다.** MySQL은 1205 때 문장만 롤백하므로 예외를 잡고 계속하지 않는다. 예외를 `@Transactional` 밖으로 던진다.
10. Spring: 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 트랜잭션이 걸리지 않는다. 트랜잭션 경계와 재시도는 별도 빈으로 나눈다.
11. 현재 시각은 앱 `Clock` 빈에서만 얻는다. SQL `NOW()`, 코드의 `Instant.now()` 금지. 시각은 파라미터로 넘긴다.
12. 일반 트랜잭션 timeout 5초, 추첨 최종화 30초.

## 2. Open / Close Worker (1초)

- Open: `SCHEDULED` + `entry_opens_at <= now` → `Event FOR UPDATE` 후 상태 재확인 → `OPEN`.
  - 서버가 멈춰 있다가 돌아와 보니 `entry_closes_at`까지 이미 지났으면 **OPEN하지 않는다.** `SCHEDULED`로 두고 오류 로그 `FANMEETING_OPEN_MISSED`를 남긴다(운영자 처리. `MANUAL_REVIEW` 상태는 P0 범위 밖). 늦게라도 마감 전이면 OPEN한다.
- Close: `OPEN` + `entry_closes_at <= now` → 한 트랜잭션에서 `Event FOR UPDATE` → `CLOSED`, `closed_at`, DrawRun(`PENDING`, 새 `random_seed`(5절), `HMAC_SHA256_RANK_V1`) 생성.

## 3. 응모

**가입 시각 검사** (트랜잭션 전): 설정 `fanmeeting.eligibility.joined-at-check.enabled`가 ON이면 `MemberPort.joinedAt(userId)`를 부른다.

- 조회 예외 → 503 `MEMBER_LOOKUP_FAILED` (저장하지 않음)
- 가입 시각 `>= first_notice_scheduled_at` → 403 `APPLICATION_NOT_ELIGIBLE`
- OFF면 검사하지 않는다.

**응모 1회 시도 = 트랜잭션 1개**

```text
Event FOR SHARE
  status = OPEN, entry_opens_at <= now < entry_closes_at  (아니면 409 APPLICATION_WINDOW_CLOSED)
SELECT application_id, status FROM fan_meeting_applications
 WHERE event_id = ? AND user_id = ?                     ← 잠금 없는 조회 (1절 8번)
  없음      → INSERT (APPLIED, applied_at = now, updated_at = now)
  APPLIED   → 409 ALREADY_APPLIED
  WITHDRAWN → SELECT ... FOR UPDATE (있는 행) → 상태 재확인
              → APPLIED, applied_at = now, updated_at = now
COMMIT
```

- 응모·취소의 현재 시각은 Event 잠금을 잡은 **뒤에** 읽는다. 잠금을 기다리는 사이 마감 시각이 지나면, 마감 Worker가 아직 돌지 않았어도 `APPLICATION_WINDOW_CLOSED`다.

**실패 처리** (트랜잭션 밖의 별도 빈)

```text
1062 중복 키 / 1213 교착 / 1205 잠금 시간 초과
  → 그 트랜잭션은 전체 롤백된 상태 (1절 9번)
  → 새 트랜잭션에서 Application 재조회
       APPLIED            → 409 ALREADY_APPLIED
       없음 또는 WITHDRAWN → 응모 시도를 다시 한다 (Event 잠금·시각 확인부터)
  → 최대 3회 (10~50ms 무작위 대기). 모두 실패하면 503 APPLICATION_BUSY
```

- **마감 판정은 커밋 기준.** 커밋돼야 성공 응답. 서버 도착 시각으로 소급하지 않는다. 정확히는 "공유 잠금 안에서 확인한 `now < entry_closes_at`이고, 마감 Worker의 CLOSED 전환보다 먼저 커밋된 응모"다. 시각 확인 뒤 잠금 대기로 커밋이 마감 시각을 조금 넘겨도, 공유 잠금 때문에 마감 Worker가 그 커밋을 기다리므로 성공이고 추첨 후보다.
- 재시도 사이에 마감 시각이 지나면 재시도는 `APPLICATION_WINDOW_CLOSED`다(커밋 기준 마감과 같다).

## 4. 응모 취소

`Event FOR SHARE`(OPEN·시각 확인) → Application 일반 조회 → 있으면 `FOR UPDATE` → `APPLIED`면 `WITHDRAWN`(`applied_at` 유지, `updated_at = now`). 없거나 이미 `WITHDRAWN`이면 409 `APPLICATION_NOT_ACTIVE`.

## 5. 추첨 (Draw Worker, 5초)

**seed와 순위 키 (바이트 규칙)**

- seed: 마감 트랜잭션에서 `SecureRandom`으로 32바이트, `random_seed BINARY(32)`.
- 순위 키: `HmacSHA256(key = seed 32바이트, message = ByteBuffer.allocate(8).putLong(application_id).array())` (8바이트 big-endian).
- 정렬: 순위 키를 `Arrays.compareUnsigned`로 오름차순, 같으면 `application_id` 오름차순.

**① Claim (트랜잭션)**

```text
Event FOR UPDATE → DrawRun FOR UPDATE
DrawRun COMPLETED면 종료
PENDING 이거나 (RUNNING + lease_until < now) 이면
  lease_token = 새 UUID, lease_until = now + 2분, attempt_count += 1, status = RUNNING, started_at(처음이면)
  Event CLOSED면 DRAWING
COMMIT
```

**② 계산 (트랜잭션 밖)**

1. `APPLIED` Application을 `application_id ASC`로 읽는다.
2. `MemberPort.activeMemberIds`로 탈퇴 회원을 뺀다(1,000개씩). 응모 행은 건드리지 않는다.
3. 순위 키로 정렬한다.
4. 앞에서 `winner_limit`명 WINNER, 다음 `reserve_limit`명 RESERVE. 순번은 1부터.

**③ 최종화 (트랜잭션)**

```text
Event FOR UPDATE → DrawRun FOR UPDATE
DrawRun COMPLETED면 아무것도 하지 않고 종료 (이미 최종화됨, 멱등. 정상 종료로 로그만)
lease_token 일치 확인 (다르면 아무것도 저장하지 않고 종료)
Selection 저장 (WINNER = PAYMENT_PENDING, RESERVE = WAITING, user_id = 응모의 user_id)
WINNER마다 Order 저장 (amount = fee_amount, issued_at = now, user_id = Selection의 user_id)
DrawRun COMPLETED, candidate_count(탈퇴 제외 후), completed_at
Event PAYMENT_IN_PROGRESS (후보 0명이면 RECRUITMENT_COMPLETED)
COMMIT
→ 커밋 뒤 WINNER Order마다 알림 요청 기록 (11절)
```

- **최종화는 멱등이다.** 같은 DrawRun에 최종화가 두 번 와도(재시도, 늦게 살아난 Worker) 먼저 `COMPLETED`를 확인하고 아무것도 저장하지 않는다. 그다음 `lease_token`을 확인한다. 순서가 바뀌면 이미 완료된 추첨에 같은 token을 가진 재시도가 Selection을 또 넣으려다 UNIQUE 위반으로 실패한다.
- 저장 중 오류가 나면 전체 롤백(일부만 남지 않음).
- seed와 `lease_token`은 응답·로그에 내보내지 않는다.
- **재현성:** 탈퇴 여부는 계산 시점에 조회한다. lease 만료 뒤 재계산은 다른 후보 집합이 될 수 있지만, 최종화는 한 번만 커밋되므로 결과는 하나다. INV-FM-05는 "같은 후보 집합이면 같은 결과"다.

## 6. 결제 시작

`POST /api/fan-meetings/{eventId}/orders/{orderId}/payment-attempts`. 헤더 `Idempotency-Key`(필수). 본문의 금액·회원·기한은 무시한다.

```text
[트랜잭션 1]
  Event FOR SHARE (없으면 404 FAN_MEETING_NOT_FOUND)
  SELECT * FROM fan_meeting_orders WHERE order_id = ? AND event_id = ? FOR UPDATE
     0건 → 404 ORDER_NOT_FOUND
  Order.user_id != 로그인 회원 → 404 ORDER_NOT_FOUND (403 아님)
  Event 모집 완료 → 409 FAN_MEETING_NOT_PAYABLE
  Order PAYMENT_REVIEW → 409 PAYMENT_UNDER_REVIEW
  Order PENDING_PAYMENT 아님 (PAID 포함) → 409 ORDER_NOT_PAYABLE
  now >= paymentDeadline() → 409 PAYMENT_WINDOW_EXPIRED
  attempt_requested_at이 NULL이면 now로 기록        ← 한 번 기록하면 지우지 않는다
COMMIT

[트랜잭션 밖]
  result = PaymentPort.startAttempt(FAN_MEETING_ORDER, orderId, userId, Order.amount, paymentDeadline(), idempotencyKey)

[트랜잭션 2] Event FOR SHARE → Order FOR UPDATE (order_id AND event_id)
  Order.payment_id가 NULL이면 result.paymentId 저장 (다른 값이 있으면 오류 로그)
COMMIT
```

- **주문 상태 확인이 먼저다(P0 정책).** 같은 키든 새 키든, 트랜잭션 1에서 주문이 `PENDING_PAYMENT`가 아니면 결제 모듈을 부르지 않고 409를 낸다. 결제 모듈 결과별 응답은 [API 계약](../../contracts/api/fanmeeting.md#결제-시작-응답)에 있다.
- `FAILED` 상태를 받은 클라이언트는 **새 키**로 다시 결제를 시작한다.
- `attempt_requested_at`은 결제 모듈 결과와 관계없이 지우지 않는다. 그래서 결제 시작을 한 번이라도 누른 주문은 포기할 수 없다(9절).

## 7. 결제 결과 반영 (`PaymentResultHandler`)

가짜 Adapter의 `deliver`가 호출한다([ports.md](ports.md)). 같은 결과가 두 번 와도 된다. Handler는 별도 빈이고 자기 트랜잭션이다.

```text
[잠그기 전] SELECT event_id FROM fan_meeting_orders WHERE order_id = ?
Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE
Order 전이표대로 판정 (순서: 상태 → 시도 ID → 금액 → 기한)
COMMIT
```

- **처음 반영하는 승인**(`approved_attempt_id`가 NULL)이면 결과(`PAID` 또는 `REFUND_PENDING`)와 함께 그 시도 ID를 기록한다.
- **이미 기록된 주문**에 승인이 오면: 같은 시도 ID면 무시, 다른 시도 ID면 이중 결제로 환불 대상 기록(상태 유지).
- **만료(`EXPIRED`) 뒤 승인:** 결제 모듈이 `NOT_APPROVED_FINAL`이라고 답한 뒤 승인이 온 것이므로 **결제 모듈의 계약 위반**이다. `approvedAt`이 기한 안이어도 자리는 되돌리지 않는다(정원 초과 방지). `REFUND_PENDING`, 시도 ID 기록, 오류 로그 `FANMEETING_APPROVAL_AFTER_FINAL`, summary 확인 목록에 올린다.
- 기한 판정: `approvedAt <= paymentDeadline()`이면 기한 안(같으면 기한 안).
- 실패(`FAILED`)는 이번 시도만 실패다. 기한이 남으면 `PENDING_PAYMENT` 유지.
- UNKNOWN/PROCESSING: `PENDING_PAYMENT`면 `PAYMENT_REVIEW`로 바꾸고 `review_started_at = now`. 이미 `PAYMENT_REVIEW`면 아무것도 바꾸지 않는다.
- 판정 코드는 한 곳(`PaymentSettlement`)에 두고 결과 반영·만료가 같이 쓴다.

## 8. 만료 Worker (1분)

대상: ① `PENDING_PAYMENT` + 기한 경과, ② `PAYMENT_REVIEW` 전부.

```text
주문마다:
  [트랜잭션 밖] result = PaymentPort.resolve(FAN_MEETING_ORDER, orderId)
  [트랜잭션]
    SELECT event_id (일반 조회) → Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE
    Order가 PENDING_PAYMENT·PAYMENT_REVIEW가 아니면 승인만 7절로 처리하고 나머지는 무시
    APPROVED                    → 7절과 같은 판정
    NOT_STARTED / NOT_APPROVED_FINAL
       result.finalDecision = true  → Order EXPIRED, Selection EXPIRED
       result.finalDecision = false → REVIEW였으면 PENDING_PAYMENT(보류 시각 NULL), 아니면 변경 없음
    PROCESSING / UNKNOWN        → PENDING_PAYMENT면 PAYMENT_REVIEW 진입(보류 시각 기록), 이미 보류면 유지
    PAYMENT_REVIEW + review_started_at + 30분 <= now + review_warned_at NULL
       → 경고 로그 FANMEETING_PAYMENT_REVIEW_STALE(orderId, 경과), review_warned_at = now
  COMMIT
```

- **만료는 결제 모듈이 "최종"이라고 답했을 때만 한다(`finalDecision = true`).** 팬미팅 시계로 최종 여부를 판단하지 않는다. 최종 여부는 결제 모듈이 자기 잠금 안에서 정한다([ports.md](ports.md)).
- 보류는 시간이 지나도 자동으로 확정·탈락시키지 않는다. P0에서는 30분 경고까지만 하고, 운영자 수동 처리는 P1이다. 테스트에서는 가짜 결제의 결과를 확정(`recordResult`)해서 보류를 푼다.

## 9. 결제 포기

`POST /api/fan-meetings/{eventId}/orders/{orderId}/decline`

```text
Event FOR SHARE
SELECT * FROM fan_meeting_orders WHERE order_id = ? AND event_id = ? FOR UPDATE   (0건 → 404 ORDER_NOT_FOUND)
Selection FOR UPDATE
다른 회원 주문 → 404 ORDER_NOT_FOUND
Order PENDING_PAYMENT 아님 → 409 ORDER_NOT_PAYABLE
attempt_requested_at NOT NULL → 409 DECLINE_NOT_ALLOWED ("결제를 시작한 주문은 포기할 수 없어요")
now >= paymentDeadline() → 409 PAYMENT_WINDOW_EXPIRED
Order CANCELLED, Selection DECLINED
COMMIT
```

- 되돌리기 없음. 빈자리는 다음 승격 회차가 채운다.
- 결제 시작(6절 트랜잭션 1)과 같은 Order 잠금으로 직렬화되므로 동시에 와도 하나만 성공한다.

보류 수동 처리는 P1이다. P0에서는 만들지 않고, 보류가 30분을 넘으면 8절의 경고 로그만 남긴다. 운영자 수동 처리(결제 모듈과 함께 확정하는 방식)는 실제 결제 연동과 같이 P1에서 만든다.

## 10. 승격 Worker (30초) + 모집 완료

대상: `PAYMENT_IN_PROGRESS`인 Event.

```text
[트랜잭션 밖] 승격 후보 미리 조회: WAITING을 rank ASC로 (빈자리 × 2)명, MemberPort.activeMemberIds로 탈퇴 여부 확인

[트랜잭션]
Event FOR UPDATE, status = PAYMENT_IN_PROGRESS 확인
occupied = Selection(CONFIRMED, PAYMENT_PENDING) 수
vacancy = winner_limit - occupied

승격 (아래 둘 다 만족할 때만):
  야간 아님 (Asia/Seoul 23:00~09:00 아님)
  now + 60분 <= promotion_closes_at
  → WAITING을 rank ASC로 FOR UPDATE 하며 하나씩:
       탈퇴자면 NOT_NEEDED로 바꾸고 다음 순번
       아니면 PAYMENT_PENDING + Order(amount = fee_amount, issued_at = now, user_id = Selection의 user_id)
     vacancy명을 채우거나 WAITING이 바닥날 때까지
     미리 조회한 범위를 넘으면 이번 회차는 거기서 멈추고 다음 회차에 이어 감

모집 완료 판정 (PAYMENT_REVIEW 0건일 때만):
  1. CONFIRMED >= winner_limit
  2. WAITING 0명이고 PAYMENT_PENDING 0명
  3. now >= promotion_closes_at 이고 PAYMENT_PENDING 0명
  → 남은 WAITING은 NOT_NEEDED, Event RECRUITMENT_COMPLETED
COMMIT
→ 커밋 뒤 승격 Order마다 알림 요청 기록 (11절)
```

- 승격은 매번 다시 세서 빈자리만큼만 하므로 여러 번·동시에 돌아도 안전하다.
- `REFUND_PENDING`은 모집 완료를 막지 않는다.
- 야간에도 모집 완료 판정은 한다.

## 11. 알림 요청 기록

실제 발송 없음. `NotificationRequestPort`에 기록(실제 구현은 구조화 로그, 테스트는 기록을 모으는 가짜).

| 언제 | 내용 |
|---|---|
| 추첨 최종화 커밋 뒤 | `WINNER`, orderId, 결제 기한 |
| 승격 커밋 뒤 | `PROMOTION`, orderId, 결제 기한 |

트랜잭션이 롤백되면 기록하지 않는다(커밋 뒤에만 기록). [ADR-0001](../../decisions/0001-event-handling.md)의 "후속 작업은 커밋 뒤에만" 원칙과 같다.
