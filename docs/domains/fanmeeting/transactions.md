# FanMeeting 트랜잭션

HTTP 응답·오류 코드는 [API 계약](../../contracts/api/fanmeeting.md)에 있다. 상태 전이 판정은 [state-machines.md](state-machines.md)를 따른다.

## 1. 잠금 규칙 (반드시 지킨다)

1. **격리 수준은 `REPEATABLE READ`**(MySQL 기본)다. 단, 결제 모듈이 부르는 Handler(6·7절)는 결제 모듈이 연 `READ_COMMITTED` 트랜잭션에서 실행된다. 그래서 Handler는 판정에 쓰는 행을 **먼저 잠근 뒤 읽는다**(잠그지 않은 조회는 바뀌지 않는 `event_id`만).
2. **팬미팅 행 중에서는 항상 Event를 먼저 잠근다.** 그다음 Application → DrawRun → Order → Selection 순서. 결제 모듈이 부르는 Handler(6·7절)는 결제 모듈이 결제 행을 먼저 잠근 상태라 `결제 행 → Event → Order → Selection`이 된다. **팬미팅 Worker와 API는 결제 행을 잠그지 않는다**(잠그면 Handler와 거꾸로 잠가 교착할 수 있다).
3. **Event를 읽고 붙잡기만 하면 `FOR SHARE`:** 응모, 응모 취소, 결제 준비·결과 반영(Handler), 만료, 결제 포기.
4. **Event를 고치거나 "세고 만드는" 일이면 `FOR UPDATE`:** 마감, 추첨 Claim·최종화, 승격(모집 완료 판정 포함).
5. **`FOR SHARE`로 잡은 트랜잭션은 Event 행을 고치지 않는다.** 공유 잠금에서 배타 잠금으로 올리면 서로 교착한다.
6. **Order로 시작하는 내부 처리**(결과 반영, 만료)는 잠그기 전에 `SELECT event_id FROM fan_meeting_orders WHERE order_id = ?`(일반 조회)로 행사를 얻고 2번 순서로 잠근다. `event_id`는 바뀌지 않는 값이다.
7. **잠금을 쥔 채 외부 호출(PG, Auth 조회)을 하지 않는다.** 외부 호출은 트랜잭션 전이나 커밋 후에 한다. Handler도 PG를 부르지 않는다(PG 호출은 결제 모듈이 트랜잭션 밖에서 한다).
8. **없는 행을 `FOR UPDATE`로 조회하지 않는다.** RR에서 빈 자리 잠금이 걸려 다른 행의 INSERT까지 막는다(3절).
9. **잠금 시간 초과(1205)·교착(1213)은 트랜잭션 전체를 롤백한다.** MySQL은 1205 때 문장만 롤백하므로 예외를 잡고 계속하지 않는다. 예외를 `@Transactional` 밖으로 던진다.
10. Spring: 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 트랜잭션이 걸리지 않는다. 트랜잭션 경계와 재시도는 별도 빈으로 나눈다.
11. 현재 시각은 앱 `Clock` 빈에서만 얻는다. SQL `NOW()`, 코드의 `Instant.now()` 금지. 시각은 파라미터로 넘긴다.
12. 일반 트랜잭션 timeout 5초, 추첨 최종화 30초.

## 2. Open / Close Worker (1초)

- Open: `SCHEDULED` + `entry_opens_at <= now` → `Event FOR UPDATE` 후 상태 재확인 → `OPEN`.
  - 서버가 멈춰 있다가 돌아와 보니 `entry_closes_at`까지 이미 지났으면 **OPEN하지 않는다.** `SCHEDULED`로 두고 오류 로그 `FANMEETING_OPEN_MISSED`를 남긴다(운영자 처리. `MANUAL_REVIEW` 상태는 P0 범위 밖). 늦게라도 마감 전이면 OPEN한다.
  - 열 행사 조회는 `entry_opens_at <= now < entry_closes_at`만 찾고, 놓친 행사는 따로 찾는다. 놓친 행사가 한 번에 처리할 건수를 차지해 열어야 할 행사가 밀리지 않게 하기 위해서다.
  - `FANMEETING_OPEN_MISSED`는 서버마다 행사당 한 번만 남긴다(1초마다 같은 로그가 쌓이지 않게). 서버를 재시작하면 한 번 더 남는다.
- 행사마다 트랜잭션을 따로 쓴다. 하나가 실패해도 나머지는 계속하고, 실패는 오류 로그와 `fanmeeting.worker.failure` 메트릭으로 남긴다.
- 설정 `restless.fanmeeting.worker.enabled`(기본 켜짐, 테스트에서는 끔), `fixed-delay-ms`(1000), `batch-size`(100).
- Close: `OPEN` + `entry_closes_at <= now` → 한 트랜잭션에서 `Event FOR UPDATE` → `CLOSED`, `closed_at`, DrawRun(`PENDING`, 새 `random_seed`(5절), `HMAC_SHA256_RANK_V1`) 생성.

## 3. 응모

**가입 시각 검사** (트랜잭션 전): 설정 `restless.fanmeeting.eligibility.joined-at-check.enabled`가 ON이면 `MemberPort.joinedAt(userId)`를 부른다.

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
1062 중복 키 / 1213 교착 / 1205 잠금 시간 초과 / 쿼리 시간 초과
  → 그 트랜잭션은 전체 롤백된 상태 (1절 9번)
  → 새 트랜잭션에서 Application 재조회
       APPLIED            → 409 ALREADY_APPLIED
       없음 또는 WITHDRAWN → 응모 시도를 다시 한다 (Event 잠금·시각 확인부터)
  → 최대 3회 (10~50ms 무작위 대기). 모두 실패하면 503 APPLICATION_BUSY
```

- **쿼리 시간 초과도 재시도한다.** 트랜잭션 timeout(5초, 1절 12번)이 MySQL `innodb_lock_wait_timeout`(기본 50초)보다 짧아서, 잠금을 오래 기다리면 1205보다 쿼리 시간 초과(`MySQLTimeoutException`)가 먼저 난다. DB 설정은 팀 공용이라 바꾸지 않는다. 최악이면 5초씩 3회, 약 15초 뒤 503이다. 잠금을 받은 직후 다음 쿼리 전에 5초가 이미 지난 경우(Spring `TransactionTimedOutException`)도 같은 이유로 재시도한다.
- 재시도는 이전 시도의 결과를 따로 재조회하지 않고 응모 시도를 처음부터 다시 한다. 응모 시도가 Event 잠금·시각 확인 뒤 내 응모를 다시 읽으므로 위 흐름(APPLIED면 409)과 같다.
- 재시도와 최종 실패는 로그 `FANMEETING_APPLY_RETRY`, `FANMEETING_APPLY_BUSY`에 이유(오류 번호 또는 `QUERY_TIMEOUT`)를 남긴다. 측정(C46)에서 건수를 센다.

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
- 최종화의 token 확인은 "DrawRun이 RUNNING이고 token이 같은가"로 한다. 그래서 완료된 추첨은 token 확인에서도 막히지만, 완료 확인을 먼저 두어 로그를 나눈다(`FANMEETING_DRAW_ALREADY_FINALIZED`, `FANMEETING_DRAW_LEASE_LOST`).
- Draw Worker는 추첨마다 따로 처리하고, 실패는 `FANMEETING_DRAW_FAILED` 로그와 `fanmeeting.worker.failure{action=draw}`로 남긴다. 실패한 추첨은 lease(2분)가 끝난 뒤 다시 가져간다. 설정 `restless.fanmeeting.worker.draw-fixed-delay-ms`(5000).
- **재현성:** 탈퇴 여부는 계산 시점에 조회한다. lease 만료 뒤 재계산은 다른 후보 집합이 될 수 있지만, 최종화는 한 번만 커밋되므로 결과는 하나다. INV-FM-05는 "같은 후보 집합이면 같은 결과"다.

## 6. 결제 시작 (공통 결제 API)

팬미팅은 결제 시작 API를 따로 두지 않는다. 손님은 **공통 결제 API**를 부르고([공통 API](../../contracts/api/common.md)), 결제 모듈이 팬미팅 Handler(`FanMeetingPaymentResultHandler`, `PayableType.FAN_MEETING`)를 부른다.

```text
POST /api/payments {payableType: FAN_MEETING, payableId: orderId}, 헤더 Idempotency-Key
[결제 모듈 트랜잭션] 결제 행 잠금 → Handler.load(orderId)
  SELECT event_id (일반 조회) → Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE
  Event PAYMENT_IN_PROGRESS 아님, 또는 Order PENDING_PAYMENT 아님 → empty (결제 모듈이 409 PAYMENT_NOT_PAYABLE)
  attempt_requested_at이 NULL이면 now로 기록        ← 한 번 기록하면 지우지 않는다
  반환: 주문 회원, Order.amount, 행사 제목, paymentDeadline()
  → 결제 모듈이 회원(다르면 404 PAYMENT_TARGET_NOT_FOUND)·기한(지났으면 409 PAYMENT_WINDOW_EXPIRED)을 확인하고 시도(READY)를 만든다
COMMIT (결제 행과 주문이 함께 커밋되거나 함께 롤백)

POST /api/payments/confirm
[결제 모듈 트랜잭션] 결제 행 잠금 → Handler.onApprovalStarting(orderId, 시도)
  같은 순서로 잠금. 잠근 뒤 읽은 now < paymentDeadline() 이고 Order PENDING_PAYMENT면
  payment_id = 이 시도, 받아들임. 아니면 거절 → 결제 모듈이 PG를 부르지 않는다
COMMIT
[트랜잭션 밖] 결제 모듈이 PG 승인 호출 → 결과를 7절 Handler로 알려 준다
```

- 금액·기한은 요청 값이 아니라 `load()`가 돌려준 서버 값이다(INV-08).
- 같은 대상에 READY 시도가 있는데 다른 키로 오면 결제 모듈이 이전 READY를 닫고(`SUPERSEDED`) 새 시도를 만든다. 승인 중(PROCESSING)이면 409 `PAYMENT_ATTEMPT_IN_PROGRESS`, 결과 모름(UNKNOWN)이면 409 `PAYMENT_UNDER_REVIEW`다. 공통 규칙을 그대로 따른다.
- 시도가 실패하면 클라이언트는 **새 키**로 다시 결제를 준비한다.
- `attempt_requested_at`은 결제 결과와 관계없이 지우지 않는다. 그래서 결제 준비를 한 번이라도 요청한 주문은 포기할 수 없다(9절).
- 참가비 0원 주문은 결제 모듈이 `FREE_ORDER`로 거절한다. 무료 주문 확정은 결제 없이 팬미팅이 처리한다(다음 작업).

## 7. 결제 결과 반영 (`FanMeetingPaymentResultHandler`)

결제 모듈이 결제 상태를 바꾸는 **같은 트랜잭션 안에서** 부른다. Handler는 `@Transactional(propagation = MANDATORY)`다. 새 트랜잭션을 열면 결제와 주문이 따로 커밋돼 원자성이 깨진다.

```text
[결제 모듈] 결제 행 잠금
[잠그기 전] SELECT event_id FROM fan_meeting_orders WHERE order_id = ?
Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE
판정(현재 시각은 잠근 뒤에 읽는다)
COMMIT (결제 모듈)
```

| 호출 | 판정 | 결과 |
|---|---|---|
| `onApproved` | `PAID`이고 같은 시도 | 받아들임(중복, 변화 없음) |
| | Order가 `PENDING_PAYMENT`·`PAYMENT_REVIEW` 아님, 또는 현재 시도(`payment_id`) 아님 | 거절, 상태 변화 없음. 결제 모듈이 보상 취소 |
| | 승인 금액 ≠ 주문 금액 | `REFUND_PENDING`, Selection `EXPIRED`, 거절 → 보상 취소 |
| | `approvedAt > 기한` | `REFUND_PENDING`, Selection `EXPIRED`, 거절 → 보상 취소 |
| | 그 외 (`approvedAt <= 기한`, 같으면 기한 안) | `PAID`(`paid_at = approvedAt`), Selection `CONFIRMED` |
| `onFailed` (승인 시작한 시도) | 현재 시도이고 `PENDING_PAYMENT`·`PAYMENT_REVIEW` | `payment_id` 비움, 보류 시각 비움. 기한 남음 → `PENDING_PAYMENT`(다시 결제 가능), 지남 → Order·Selection `EXPIRED` |
| `onFailed` (승인 시작 전에 닫힌 시도) | `PENDING_PAYMENT`이고 진행 중인 시도 없음 | 위와 같은 기한 판정 |
| `onUnknown` | 현재 시도이고 `PENDING_PAYMENT` | `PAYMENT_REVIEW`, `review_started_at = now`. 이미 보류면 변화 없음 |
| `onRefunded` (보상 취소) | `REFUND_PENDING`이고 그 시도 | `REFUNDED` |
| | 그 외(현재 시도가 아니어서 거절한 승인의 환불) | 변화 없음, 로그 |

- 처음 반영하는 승인은 결과(`PAID` 또는 `REFUND_PENDING`)와 함께 `approved_attempt_id`에 그 시도를 기록한다.
- 결과 모름은 실패로 처리하지 않는다. 최종 결과는 결제 모듈의 대사 작업이 **같은 시도로** `onApproved` 또는 `onFailed`로 다시 알려 준다.
- **만료(`EXPIRED`) 뒤 승인:** 결제 모듈은 실패로 닫힌 시도를 승인하지 않으므로 계약 위반이다. 거절해 보상 취소하고 주문·선발은 `EXPIRED`로 둔다(정원 초과 방지, 자리를 되돌리지 않는다). 로그 `FANMEETING_APPROVAL_NOT_CURRENT`.
- 손님 환불(`onRefundRequesting`)은 P0 범위 밖이라 기본 구현(거절)을 쓴다.
- 거절 사유: `FANMEETING_NOT_PAYABLE`, `FANMEETING_ORDER_NOT_FOUND`, `FANMEETING_STALE_ATTEMPT`, `FANMEETING_AMOUNT_MISMATCH`, `FANMEETING_LATE_APPROVAL`.

## 8. 만료 Worker (1분)

결제를 준비한 시도의 결과(승인·실패·결제창만 열고 기한 지남)는 결제 모듈이 Handler로 알려 준다(7절). 만료 Worker는 **결제 모듈이 알려 줄 수 없는 주문**만 맡는다.

대상: ① `PENDING_PAYMENT` + 기한 경과 + 진행 중인 시도 없음(`payment_id IS NULL`: 결제를 준비하지 않았거나, 실패 뒤 다시 결제하지 않음), ② `PAYMENT_REVIEW` 30분 경고.

```text
주문마다:
  [트랜잭션]
    SELECT event_id (일반 조회) → Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE
    ① 다시 확인(PENDING_PAYMENT, 기한 경과, payment_id NULL) → Order EXPIRED, Selection EXPIRED
    ② PAYMENT_REVIEW + review_started_at + 30분 <= now + review_warned_at NULL
       → 경고 로그 FANMEETING_PAYMENT_REVIEW_STALE(orderId, 경과), review_warned_at = now
  COMMIT
```

- 결제 행은 잠그지 않는다(1절 2번). 남은 READY 시도는 승인 시작 때 Handler가 Order를 잠그고 기한을 다시 보므로, 만료된 주문은 승인되지 않는다.
- 보류는 시간이 지나도 자동으로 확정·탈락시키지 않는다. P0에서는 30분 경고까지만 하고, 운영자 수동 처리는 P1이다.

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
- 결제 준비(6절 `load`)와 같은 Order 잠금으로 직렬화되므로 동시에 와도 하나만 성공한다.

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
