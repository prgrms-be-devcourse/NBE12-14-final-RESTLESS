# Auction 트랜잭션 경계

## 공통 규칙

- **잠금 순서:** 경매 행 → 지갑 행(지갑 ID 오름차순). 모든 트랜잭션이 이 순서를 지켜 데드락을 막는다.
- 트랜잭션 안에서 외부 호출(HTTP, SSE 전송)을 하지 않는다.
- 실시간 전달은 커밋 후 이벤트(AFTER_COMMIT)로만 한다. 실패해도 확정된 상태를 되돌리지 않는다 ([ADR-0001](../../decisions/0001-event-handling.md)).
- 현재 시각은 주입받은 `Clock`으로 읽는다. 한 트랜잭션 안에서는 시작할 때 읽은 시각 하나를 쓴다.
- 업무 거절(금액 부족 등)은 아무것도 바꾸지 않고 롤백한다.

## TX-AU-1. 크레딧 지급 (테스트용)

하나의 트랜잭션에서:

1. 회원의 지갑을 찾는다. 없으면 만든다. 동시에 만들어 `UNIQUE(user_id)`에 걸리면 다시 조회한다.
2. 지갑 행 `FOR UPDATE`
3. `balance_amount += 10,000`
4. `credit_transactions` `GRANT` 기록 (`operation_key`는 서버가 만든 UUID)
5. 커밋

운영(prod) 프로필에서는 이 API를 등록하지 않는다.

## TX-AU-2. 입찰

하나의 트랜잭션에서:

1. **경매 행 `FOR UPDATE`.** 같은 경매의 입찰과 종료가 여기서 한 줄로 선다.
2. **중복 요청 확인:** `(bidder_user_id, request_key)`로 기존 입찰을 찾는다. 있으면 그 행의 `auction_id`, `bid_amount`를 요청과 비교한다. 같으면 처음 결과를 돌려주고 끝내고, 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 수락된 입찰에 경매 ID와 금액이 저장되므로 요청 해시를 따로 저장하지 않는다
3. **시각 확인:** `starts_at <= 지금 < ends_at`. 아니면 `AUCTION_NOT_OPEN` 또는 `AUCTION_CLOSED`
4. **입찰자 확인:** 판매자 본인이면 `BID_BY_SELLER`. 현재 최고 입찰자면 `BID_ALREADY_HIGHEST`
5. **금액 확인:** 입찰이 없으면 시작가 이상, 있으면 최고가 + 최소 인상액 이상. 아니면 `BID_AMOUNT_TOO_LOW`(최소 입찰 가능 금액 포함)
6. **지갑 잠금:** 새 입찰자와 이전 최고 입찰자의 지갑을 ID 오름차순으로 `FOR UPDATE`
7. **크레딧 확인:** 새 입찰자의 `balance − reserved >= 입찰 금액`. 아니면 `CREDIT_INSUFFICIENT`. 지갑이 없으면 0으로 본다. 4번에서 현재 최고 입찰자를 거절했으므로 새 입찰자와 이전 최고 입찰자는 항상 다른 사람이다. 따라서 10번의 이전 확보 해제는 새 입찰자의 사용 가능액에 영향이 없고, 해제 전에 확인해도 결과가 같다
8. **입찰 저장:** `bid_sequence = 이전 최대 + 1`, `accepted_at = 지금`
9. **새 확보:** `credit_holds` `HELD`, 지갑 `reserved += 금액`, 내역 `HOLD`
10. **이전 확보 해제:** 이전 최고 입찰의 확보를 `RELEASED`, 그 지갑 `reserved −= 금액`, 내역 `RELEASE`
11. **경매 갱신:** `ends_at = max(ends_at, 지금 + 연장 시간)`, `revision += 1`
12. 커밋 → `AuctionChangedEvent` 발행

8~11 중 하나라도 실패하면 전체를 롤백한다. 일부만 반영된 상태는 남지 않는다.

`operation_key`는 `HOLD:<입찰 ID>`, `RELEASE:<확보 ID>`처럼 대상 ID로 만든다. 같은 작업이 두 번 기록되면 UNIQUE에서 실패한다.

**중복 요청 경합:**

- 같은 키가 **같은 경매**로 동시에 오면 1번 잠금에서 한 줄로 서므로, 두 번째 요청은 2번에서 첫 입찰을 찾는다.
- 같은 키가 **다른 경매**로 동시에 오면 서로 다른 경매 행을 잠그므로 잠금이 막지 못한다. 이때는 8번 INSERT가 `UNIQUE(bidder_user_id, request_key)`에 걸리고, 이 위반을 `IDEMPOTENCY_KEY_CONFLICT`로 응답한다.
- 거절된 요청은 저장되지 않으므로, 같은 키로 재시도하면 처음부터 다시 판단한다.

## TX-AU-3. 경매 시작 (스케줄러)

1초마다 `status = 'SCHEDULED' AND starts_at <= 지금`인 경매를 찾고, 경매마다:

```sql
UPDATE auctions SET status = 'OPEN', revision = revision + 1, updated_at = ?
WHERE auction_id = ? AND status = 'SCHEDULED'
```

바뀐 행이 있으면 커밋 후 `AuctionChangedEvent`를 발행한다. 조건부 UPDATE이므로 두 번 실행돼도 한 번만 바뀐다.

## TX-AU-4. 경매 종료 (스케줄러)

1초마다 `status IN ('SCHEDULED', 'OPEN') AND ends_at <= 지금`인 경매 ID를 찾고, **경매마다 별도 트랜잭션**으로:

1. 경매 행 `FOR UPDATE`
2. 이미 `CLOSED`면 끝낸다 (다른 실행이 처리함)
3. `ends_at`을 다시 확인한다. 그사이 연장돼 `지금 < ends_at`이면 끝낸다
4. 최고 입찰을 찾는다
   - **없으면:** 결과 `NO_BID` 저장
   - **있으면:** 낙찰자 지갑 `FOR UPDATE` → 확보 `CAPTURED` → 지갑 `balance −= 금액`, `reserved −= 금액` → 내역 `CAPTURE` → 결과 `WON` 저장
5. 경매 `CLOSED`, `closed_at = 지금`, `revision += 1`
6. 커밋 → `AuctionChangedEvent` 발행

한 경매의 종료가 실패해도 다른 경매의 종료에는 영향이 없다. 실패한 경매는 다음 주기에 다시 시도한다.

## TX-AU-5. 경매 수정, 삭제

1. 경매 행 `FOR UPDATE`
2. 본인(`seller_user_id`) 또는 ADMIN인지 확인. 아니면 `AUCTION_FORBIDDEN`
3. `지금 < starts_at`인지 확인. 아니면 `AUCTION_ALREADY_STARTED`
4. 수정이면 값을 검증한 뒤 바꾸고, 삭제면 행을 지운다
5. 커밋

잠금 안에서 시작 시각을 확인하므로, 시작 스케줄러나 입찰과 경합해도 시작된 경매는 바뀌지 않는다.

## 실시간 전달 (트랜잭션 밖)

```text
AuctionChangedEvent (AFTER_COMMIT)
  → 경매 ID를 "변경됨"으로 표시
0.2초마다
  → 변경된 경매마다 최신 상태를 조회해 그 경매의 SSE 연결 전체에 전송
```

전송은 커밋된 데이터만 읽는다. 서버가 커밋 직후 종료돼 전송을 못 하더라도, 클라이언트는 재접속할 때 상세 API로 최신 상태를 받는다.

## 장애 시나리오

| 상황 | 결과 |
|---|---|
| 입찰 트랜잭션 중 서버 종료 | 전체 롤백. 입찰, 확보, 연장 모두 없음. 클라이언트가 같은 키로 재시도하면 새로 처리한다 |
| 입찰 커밋 후 응답 전 연결 끊김 | 클라이언트가 같은 키로 재시도하면 처음 결과를 돌려준다 |
| 종료 트랜잭션 중 서버 종료 | 전체 롤백. 경매는 `OPEN`으로 남고 재시작 후 다음 주기에 종료된다 |
| 종료 스케줄러가 두 번 실행됨 | 두 번째 실행은 잠금 뒤 `CLOSED`를 보고 끝낸다. 결과 PK가 중복 저장을 막는다 |
| 종료 직전 입찰과 종료가 경합 | 스케줄러가 종료 후보를 조회한 뒤 트랜잭션을 시작하기 전에 새 입찰이 들어와 `ends_at`을 늘릴 수 있다. 종료 트랜잭션은 잠금을 얻은 **뒤** `ends_at`을 다시 확인하므로, 연장됐으면 종료하지 않는다. 종료가 먼저 잠금을 얻어 커밋하면, 그 뒤 입찰은 잠금 안의 시각 확인에서 `AUCTION_CLOSED`로 거절된다 |
| SSE 전송 실패 | 로그와 메트릭을 남긴다. 입찰과 낙찰은 그대로다 |
