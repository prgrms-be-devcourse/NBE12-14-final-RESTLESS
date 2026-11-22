# Auction 트랜잭션 경계

> 관련 코드: `backend/src/main/java/com/restless/auction/application/`, `backend/src/main/java/com/restless/credit/application/`
>
> 마지막 확인: 2026-11-21 (#316 크레딧 충전 TX-AU-6 추가)

## 공통 규칙

- **잠금 순서:** 경매 행 → 지갑 행(지갑 ID 오름차순). 모든 트랜잭션이 이 순서를 지켜 데드락을 막는다.
  충전 승인(TX-AU-6)은 결제 행 → 충전 주문 행 → 지갑 행이고 경매 행을 잠그지 않는다. 두 순서 모두 지갑 행이 마지막이라 순환하지 않는다.
- 트랜잭션 안에서 외부 호출(HTTP, SSE 전송)을 하지 않는다.
- 실시간 전달은 커밋 후 이벤트(AFTER_COMMIT)로만 한다. 실패해도 확정된 상태를 되돌리지 않는다 ([ADR-0001](../../decisions/0001-event-handling.md)).
- 현재 시각은 주입받은 `Clock`으로 읽는다. **시각으로 판단하는 트랜잭션은 경매 행 잠금을 얻은 뒤에 시각을 읽고**, 그 트랜잭션 안에서는 그 시각 하나를 쓴다. 잠금 전에 읽으면 잠금을 기다리는 사이 흐른 시간이 판단에 빠진다.
- 업무 거절(금액 부족 등)은 아무것도 바꾸지 않고 롤백한다.

## TX-AU-1. 크레딧 지급 (테스트용)

하나의 트랜잭션에서:

1. 회원의 지갑이 없으면 만든다. `INSERT ... ON DUPLICATE KEY UPDATE`라서 동시에 만들어도 오류 없이 `UNIQUE(user_id)`에 따라 한 행만 생긴다.
2. 지갑 행 `FOR UPDATE`
3. `balance_amount += 10,000`
4. `credit_transactions` `GRANT` 기록 (`operation_key`는 서버가 만든 UUID)
5. 커밋

운영(prod) 프로필에서는 이 API를 등록하지 않는다.

## TX-AU-2. 입찰

### 잠금 전 금액 확인 (트랜잭션 밖)

몰림에서 거절될 입찰이 경매 행 잠금을 기다리지 않게 한다. 조회 캐시가 켜져 있을 때만 하고, 아래를 **모두** 만족하면 트랜잭션을 시작하지 않고 `BID_AMOUNT_TOO_LOW`로 거절한다.

1. 캐시의 경매가 `OPEN`이고 `starts_at <= 지금 < ends_at`
2. 입찰자가 판매자가 아니고, 캐시의 현재 최고 입찰자도 아니다
3. 입찰 금액이 캐시의 최소 입찰 가능 금액보다 낮다
4. `(bidder_user_id, request_key)`로 찾은 기존 입찰이 없다

하나라도 아니면 아래 트랜잭션으로 넘어가 처음부터 다시 판단한다. 경매가 없거나 캐시·요청 키 조회가 실패해도 입찰을 막지 않고 트랜잭션으로 넘긴다. 조회 실패는 로그와 `auction.bid.precheck.failure`로 남긴다.

`OPEN` 경매의 최소 입찰 금액과 `ends_at`은 줄지 않는다. 그래서 조금 오래된 값보다 낮은 금액은 지금도 부족하고, 오래된 `ends_at` 전이면 실제 경매도 아직 끝나지 않았다. 요청 키는 금액 부족 후보만 확인해, 수락될 수 있는 입찰의 DB 조회를 늘리지 않는다.

### 입찰 트랜잭션

하나의 트랜잭션에서:

1. **경매 행 `FOR UPDATE`.** 같은 경매의 입찰과 종료가 여기서 한 줄로 선다.
2. **중복 요청 확인:** `(bidder_user_id, request_key)`로 기존 입찰을 찾는다. 있으면 그 행의 `auction_id`, `bid_amount`를 요청과 비교한다. 같으면 처음 결과를 돌려주고 끝내고, 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 수락된 입찰에 경매 ID와 금액이 저장되므로 요청 해시를 따로 저장하지 않는다
3. **시각 확인:** `starts_at <= 지금 < ends_at`. 아니면 `AUCTION_NOT_OPEN` 또는 `AUCTION_CLOSED`
4. **입찰자 확인:** 판매자 본인이면 `BID_BY_SELLER`. 현재 최고 입찰자면 `BID_ALREADY_HIGHEST`
5. **금액 확인:** 입찰이 없으면 시작가 이상, 있으면 최고가 + 최소 인상액 이상. 아니면 `BID_AMOUNT_TOO_LOW`(최소 입찰 가능 금액 포함)
6. **지갑 잠금:** 새 입찰자와 이전 최고 입찰자의 지갑을 ID 오름차순으로 `FOR UPDATE`
7. **입찰 저장:** `bid_sequence = 이전 최대 + 1`, `accepted_at = 지금`. `UNIQUE(bidder_user_id, request_key)` 위반은 `IDEMPOTENCY_KEY_CONFLICT`
8. **크레딧 확인과 새 확보:** 새 입찰자의 `balance − reserved >= 입찰 금액`이어야 한다. 아니면 `CREDIT_INSUFFICIENT`. 지갑이 없으면 0으로 본다. 통과하면 `credit_holds` `HELD`, 지갑 `reserved += 금액`, 내역 `HOLD`
9. **이전 확보 해제:** 이전 최고 입찰의 확보를 `RELEASED`, 그 지갑 `reserved −= 금액`, 내역 `RELEASE`
10. **경매 갱신:** `ends_at = max(ends_at, 지금 + 연장 시간)`, `revision += 1`
11. 커밋 → `AuctionChangedEvent` 발행

7~10 중 하나라도 실패하면 전체를 롤백한다. 일부만 반영된 상태는 남지 않는다.

- 크레딧 확인은 입찰을 저장한 뒤 8번에서 한다. 확보 행이 입찰 ID를 가리키기 때문이다. 부족하면 7번에서 저장한 입찰도 함께 롤백되므로 결과는 "아무것도 저장하지 않음"이다. 다만 MySQL AUTO_INCREMENT 번호는 돌려받지 않아 입찰 ID에 빈 번호가 생긴다.
- 4번에서 현재 최고 입찰자를 거절했으므로 새 입찰자와 이전 최고 입찰자는 항상 다른 사람이다. 따라서 9번의 이전 확보 해제는 새 입찰자의 사용 가능액에 영향이 없고, 해제 전에 확인해도 결과가 같다.

`operation_key`는 `HOLD:<입찰 ID>`, `RELEASE:<확보 ID>`처럼 대상 ID로 만든다. 같은 작업이 두 번 기록되면 UNIQUE에서 실패한다.

**중복 요청 경합:**

- 같은 키가 **같은 경매**로 동시에 오면 1번 잠금에서 한 줄로 서므로, 두 번째 요청은 2번에서 첫 입찰을 찾는다.
- 같은 키가 **다른 경매**로 동시에 오면 서로 다른 경매 행을 잠그므로 잠금이 막지 못한다. 이때는 7번 INSERT가 `UNIQUE(bidder_user_id, request_key)`에 걸리고, 이 위반을 `IDEMPOTENCY_KEY_CONFLICT`로 응답한다.
- 거절된 요청은 저장되지 않으므로, 같은 키로 재시도하면 처음부터 다시 판단한다.

## TX-AU-3. 경매 시작 (스케줄러)

1초마다 `status = 'SCHEDULED' AND starts_at <= 지금`인 경매 ID를 찾고, **경매마다 별도 트랜잭션**으로:

1. 경매 행 `FOR UPDATE`
2. 아직 `SCHEDULED`이고 `starts_at <= 지금 < ends_at`이면 `OPEN`, `revision += 1`
   - 종료 시각까지 지났으면 열지 않는다. 종료(TX-AU-4)가 `SCHEDULED`에서 바로 닫는다.
3. 커밋 → `AuctionChangedEvent` 발행

잠금 뒤 상태를 다시 보므로 두 번 실행돼도 한 번만 바뀐다.

**스케줄러 설정** (`application.yml` `restless.auction.scheduler`): `enabled`(기본 true), `fixed-delay-ms`(기본 1000), `batch-size`(한 번에 처리할 경매 수, 기본 100). 테스트에서는 `build.gradle`이 `enabled=false`로 끄고, 스케줄러 테스트만 다시 켠다. 경매 하나가 실패하면 로그와 `auction.scheduler.failure`(`action` = `open`, `close`) 메트릭을 남기고 다음 경매를 계속 처리한다 (`AuctionCloseFailureTest`).

**실행 스레드:** 모든 `@Scheduled` 작업은 Spring의 공용 실행기를 같이 쓴다. 스레드가 1개면 결제 대사가 PG 응답을 기다리는 동안 경매 시작·종료가 실행되지 못하므로, `spring.task.scheduling.pool.size`를 4로 둔다 (#315, `AuctionSchedulerIsolationTest`).

## TX-AU-4. 경매 종료 (스케줄러)

1초마다 `status IN ('SCHEDULED', 'OPEN') AND ends_at <= 지금`인 경매 ID를 찾고, **경매마다 별도 트랜잭션**으로:

1. 경매 행 `FOR UPDATE`
2. 이미 `CLOSED`면 끝낸다 (다른 실행이 처리함)
3. `ends_at`을 다시 확인한다. 그사이 연장돼 `지금 < ends_at`이면 끝낸다
4. 최고 입찰을 찾는다
   - **없으면:** 결과 `UNSOLD`(유찰) 저장
   - **있으면:** 낙찰자 지갑 `FOR UPDATE` → 확보 `CAPTURED` → 지갑 `balance −= 금액`, `reserved −= 금액` → 내역 `CAPTURE` → 결과 `SOLD`(낙찰) 저장
5. 경매 `CLOSED`, `closed_at = 지금`, `revision += 1`
6. 커밋 → `AuctionChangedEvent` 발행

한 경매의 종료가 실패해도 다른 경매의 종료에는 영향이 없다. 실패한 경매는 다음 주기에 다시 시도한다.

## TX-AU-5. 경매 수정, 삭제

1. 경매 행 `FOR UPDATE`
2. 본인(`seller_user_id`) 또는 ADMIN인지 확인. 아니면 `AUCTION_FORBIDDEN`
3. `지금 < starts_at`인지 확인. 아니면 `AUCTION_ALREADY_STARTED`
4. 수정이면 값을 검증한 뒤 바꾸고(`revision += 1`), 삭제면 행을 지운다
5. 커밋 → `AuctionChangedEvent` 발행 (시작 전 화면을 보던 연결에 바뀐 상태를 보내거나, 삭제면 연결을 닫는다)

잠금을 얻은 뒤 읽은 시각으로 시작 시각을 확인하므로, 시작 스케줄러나 입찰과 경합해도 시작된 경매는 바뀌지 않는다. 잠금을 기다리는 사이 시작 시각이 지나면 `AUCTION_ALREADY_STARTED`다 (`SellerAuctionLockOrderTest`).

## TX-AU-6. 크레딧 충전 (PG 결제, #316)

충전 주문 생성과 지급은 다른 트랜잭션이다. 사이의 PG 호출은 공통 결제 모듈이 트랜잭션 밖에서 한다 ([payment.md](../../contracts/api/payment.md), [ADR-0003](../../decisions/0003-payment-module-boundary.md)).

**충전 주문 생성** (`POST /api/me/credit-charges`)

1. 같은 회원·같은 요청 키의 충전 주문이 있으면 금액을 비교해 돌려준다 (같으면 200, 다르면 `IDEMPOTENCY_KEY_CONFLICT`)
2. 없으면 `READY`, `expires_at = 지금 + 30분`으로 저장한다
3. 같은 키가 동시에 와 `UNIQUE(user_id, request_key)`에 막히면, 그 저장 트랜잭션이 롤백된 뒤 새로 조회해 1과 같이 돌려준다. 그래서 이 단계는 하나의 트랜잭션으로 감싸지 않는다

**결제 결과 반영** (결제 모듈이 결제 행을 잠근 트랜잭션 안에서 결과 처리기 호출, `MANDATORY`)

| 호출 | 동작 |
|---|---|
| 결제 준비 (`load`) | `READY`면 회원·금액·주문명(`크레딧 50,000`)·`expires_at`을 준다. `PAID`이거나 없으면 결제 대상이 아니다. 기한은 결제 모듈이 `expires_at`으로 판정한다 |
| 승인 시작 | 충전 주문 행 `FOR UPDATE` 뒤 시각을 읽어 `READY`이고 기한 안이면 받는다 |
| 승인 확인 | 충전 주문 행 `FOR UPDATE`. `READY`면 지갑 생성(없으면) → 지갑 행 `FOR UPDATE` → `balance += 금액` → 내역 `CHARGE`(`operation_key = CHARGE:<충전 주문 ID>`) → `PAID`. **기한이 지났어도 지급한다.** 같은 결제로 이미 `PAID`면 받기만 하고, 다른 결제로 `PAID`이거나 승인 금액이 다르면 거절해 결제 모듈이 보상 취소한다 |
| 실패, 결과 불명확 | 아무것도 바꾸지 않는다. 불명확은 결제 모듈의 미완료 결제 재확인 작업이 나중에 승인·실패로 다시 알려 준다 |
| 환불 요청 | 기본 거절 `REFUND_NOT_SUPPORTED` |
| 환불 완료 | 보상 취소만 올 수 있다. 충전 주문·지갑을 바꾸지 않는다 |

지급 중 DB 오류는 예외로 던진다. 지갑, 내역, 충전 주문, 결제 상태가 함께 롤백되고 결제는 처리 중·불명확으로 남아 재확인 작업이 다시 확정한다 (`CreditChargeFailureTest`).

## 실시간 전달 (트랜잭션 밖)

```text
AuctionChangedEvent (AFTER_COMMIT)
  → 그 경매에 연결이 있으면 경매 ID를 "변경됨"으로 표시
0.2초마다 (전용 스레드 하나)
  → 변경된 경매마다 최신 상태를 한 번 조회해 그 경매의 SSE 연결 전체에 전송
  → CLOSED 상태를 보냈으면 연결을 닫는다. 경매가 삭제됐으면 보내지 않고 닫는다
```

전송은 커밋된 데이터만 읽는다. 서버가 커밋 직후 종료돼 전송을 못 하더라도, 클라이언트는 재접속할 때 상세 API로 최신 상태를 받는다.

구현은 `auction/api/AuctionStreams`다. 연결은 이 서버 메모리에만 있다.

- **연결 순서:** 연결을 먼저 등록한 뒤 현재 상태를 조회해 보낸다. 조회와 등록 사이에 바뀐 내용은 다음 전송에 다시 가고, 클라이언트는 같은 `revision`을 무시한다.
- **전송 내용:** 한 번의 전송에서 상태 JSON은 한 번만 만들고, 같은 문자열을 그 경매의 연결 전체에 보낸다. JSON을 만들지 못하면 그 경매의 이번 전송은 건너뛰고 `flush` 실패로 기록하며 연결은 유지한다. 연결 직후 첫 전송에서 실패하면 그 연결을 등록해 두지 않는다.
- **전송 스레드:** 경매 종료 스케줄러, 미완료 결제 재확인 작업과 스레드를 나누지 않으면 0.2초 간격이 밀리므로 전용 스레드를 쓴다. 한 경매의 전송이 실패해도 다른 경매는 계속 보낸다.
- **설정** (`application.yml` `restless.auction.stream`): `flush-interval-ms`(기본 200), `timeout-ms`(연결 최대 유지 시간, 기본 30분. 지나면 서버가 닫고 클라이언트가 재연결한다).
- **메트릭:** `auction.stream.connections`(열린 연결 수), `auction.stream.failure`(`stage` = `send` 끊긴 연결로 전송 실패, `flush` 한 경매 조회·전송 실패, `loop` 전송 주기 자체 실패).

## 조회 캐시 (트랜잭션 밖)

인기 경매에 같은 조회가 몰릴 때 DB 조회를 줄인다. 구현은 `auction/application/AuctionQueryCache`이고, 캐시는 이 서버 메모리에만 있다.

| 대상 | 캐시 키 | 새로 읽는 때 |
|---|---|---|
| 상세 | 경매 ID + 세대 번호 | 경매가 바뀌어 세대 번호가 오른 뒤, 또는 TTL(기본 1초) 뒤 |
| 목록 | 상태 + 페이지 + 크기 | TTL(기본 1초) 뒤 |

```text
AuctionChangedEvent (AFTER_COMMIT)
  → 그 경매의 세대 번호를 올린다
다음 상세 조회
  → 새 세대의 캐시가 없으므로 커밋된 최신 상태를 읽는다
```

- 입찰, 시작, 종료, 수정, 삭제가 이 이벤트를 발행한다. 삭제는 `revision`이 오르지 않으므로 `revision`을 캐시 키로 쓰지 않는다.
- 세대 번호는 커밋 뒤에만 오른다. 롤백된 변경은 캐시에 영향을 주지 않는다. 이벤트 처리는 캐시 조회를 기다리지 않는다.
- 같은 키를 동시에 조회하면 한 요청만 DB를 읽고 나머지는 그 결과를 쓴다.
- 상세의 `serverTime`은 캐시하지 않고 응답할 때 넣는다.
- 경매 이벤트가 없는 닉네임 변경은 TTL 뒤에 반영된다.
- 입찰의 최종 판단, SSE 상태 조회, 결과 조회, 내 입찰 조회, 주최자 수정 응답은 이 캐시를 쓰지 않는다. 입찰의 잠금 전 금액 확인만 같은 세대 번호와 TTL을 쓰는 내부 상태를 읽는다.
- **설정** (`application.yml` `restless.auction.query-cache`): `enabled`(기본 true, 테스트는 `build.gradle`이 끈다), `ttl-ms`(기본 1000).

## 장애 시나리오

| 상황 | 결과 |
|---|---|
| 입찰 트랜잭션 중 서버 종료 | 전체 롤백. 입찰, 확보, 연장 모두 없음. 클라이언트가 같은 키로 재시도하면 새로 처리한다 |
| 입찰 커밋 후 응답 전 연결 끊김 | 클라이언트가 같은 키로 재시도하면 처음 결과를 돌려준다 |
| 종료 트랜잭션 중 서버 종료 | 전체 롤백. 경매는 종료 전 상태(`OPEN` 또는 `SCHEDULED`)로 남고 재시작 후 다음 주기에 종료된다 |
| 종료 스케줄러가 두 번 실행됨 | 두 번째 실행은 잠금 뒤 `CLOSED`를 보고 끝낸다. 결과 PK가 중복 저장을 막는다 |
| 종료 직전 입찰과 종료가 경합 | 스케줄러가 종료 후보를 조회한 뒤 트랜잭션을 시작하기 전에 새 입찰이 들어와 `ends_at`을 늘릴 수 있다. 종료 트랜잭션은 잠금을 얻은 **뒤** `ends_at`을 다시 확인하므로, 연장됐으면 종료하지 않는다. 종료가 먼저 잠금을 얻어 커밋하면, 그 뒤 입찰은 잠금 안의 시각 확인에서 `AUCTION_CLOSED`로 거절된다 |
| SSE 전송 실패 | 로그와 메트릭을 남긴다. 입찰과 낙찰은 그대로다 |
| 충전 승인 뒤 지급 반영 중 서버 종료나 DB 오류 | 지급·충전 주문 변경이 롤백되고 결제는 처리 중·불명확으로 남는다. 재확인 작업이 PG를 다시 조회해 승인이면 한 번 지급한다 |
| 충전 결제 결과를 모름 | 지급하지 않고 기다린다. 그동안 같은 충전 주문의 새 결제 준비는 `PAYMENT_UNDER_REVIEW` |
