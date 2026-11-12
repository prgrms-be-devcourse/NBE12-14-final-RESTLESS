# Auction 불변식

동시 요청이나 재시도가 있어도 반드시 지켜야 하는 조건이다. 단위, 통합, 동시성 테스트의 기준이며, 테스트 이름이나 주석에 `INV-AU-xx`를 붙여 추적한다.

INV-AU-01 ~ 13은 원본([design.md](design.md) 8절)이고, 14 ~ 15는 크레딧 장부를 검증하려고 추가했다.

| ID | 불변식 | 지키는 방법 |
|---|---|---|
| INV-AU-01 | 경매당 현재 최고 입찰은 최대 하나다 | 입찰은 경매 행 `FOR UPDATE` 안에서 처리한다. 최고 입찰 = 그 경매의 가장 큰 `bid_sequence`. `UNIQUE(auction_id, bid_sequence)` |
| INV-AU-02 | 경매당 결과는 최대 하나이고, 낙찰자는 최대 한 명이다 | `auction_results` PK = `auction_id`, `UNIQUE(winning_bid_id)`. 종료는 경매 행 잠금 뒤 상태를 다시 확인한다 |
| INV-AU-03 | 입찰 금액은 가격 조건을 충족한다 (첫 입찰은 시작가 이상, 이후는 최고가 + 최소 인상액 이상) | 경매 행 잠금 안에서 검증한다 |
| INV-AU-04 | 판매자는 자기 경매에 입찰할 수 없다 | 잠금 안에서 검증한다 |
| INV-AU-05 | 현재 최고 입찰자는 자기 최고가를 갱신할 수 없다 | 잠금 안에서 검증한다 |
| INV-AU-06 | 사용 가능 크레딧은 음수가 될 수 없다 | 지갑 행 `FOR UPDATE` 안에서 검증한다. DB CHECK `reserved_amount <= balance_amount` |
| INV-AU-07 | 진행 중인 경매의 최고 입찰 금액은 확보돼 있다 | 입찰 저장과 확보를 한 트랜잭션에서 한다. 최고 입찰마다 `HELD` 확보가 정확히 1건 있다 |
| INV-AU-08 | 추월된 입찰의 확보는 해제돼 있다 | 새 입찰 수락과 이전 확보 해제를 한 트랜잭션에서 한다 |
| INV-AU-09 | 화면 이탈이나 연결 끊김으로 입찰이 무효화되지 않는다 | 입찰과 확보는 연결 상태를 참조하지 않는다. SSE는 읽기 전용이다 |
| INV-AU-10 | 연장으로 종료 시각이 앞당겨지지 않는다 | `ends_at = max(ends_at, 수락 시각 + 연장 시간)`. DB CHECK `base_ends_at <= ends_at` |
| INV-AU-11 | 종료 시각 이후의 입찰은 수락되지 않는다 | 입찰은 잠금 안에서 `지금 < ends_at`을 확인한다. 종료는 같은 잠금을 잡고 `ends_at <= 지금`을 다시 확인한다 |
| INV-AU-12 | 같은 요청은 중복 반영되지 않는다 (입찰, 확보, 차감, 연장) | 입찰 `UNIQUE(bidder_user_id, request_key)`, 크레딧 내역 `UNIQUE(operation_key)`, 확보 `UNIQUE(auction_bid_id)`, 결과 PK |
| INV-AU-13 | 낙찰 결과와 크레딧 차감은 함께 반영된다 | 결과 저장, 확보 `CAPTURED`, 지갑 차감을 한 트랜잭션에서 한다 |
| INV-AU-14 | 지갑의 `reserved_amount` = 그 지갑의 `HELD` 확보 금액 합계 | 확보·해제·차감 때 지갑과 확보를 같은 트랜잭션에서 바꾼다 |
| INV-AU-15 | 지갑의 `balance_amount`, `reserved_amount` = 그 지갑의 `credit_transactions` 변화량 합계 | 지갑을 바꿀 때마다 내역을 같은 트랜잭션에서 한 건 기록한다 |

## 테스트에서 확인하는 SQL

동시성 테스트 끝에 아래 쿼리가 모두 0건이어야 한다.

```sql
-- INV-AU-07: 진행 중 경매의 최고 입찰에 HELD 확보가 없음
SELECT a.auction_id
FROM auctions a
JOIN auction_bids b ON b.auction_id = a.auction_id
 AND b.bid_sequence = (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = a.auction_id)
LEFT JOIN credit_holds h ON h.auction_bid_id = b.auction_bid_id AND h.status = 'HELD'
WHERE a.status <> 'CLOSED' AND h.credit_hold_id IS NULL;

-- INV-AU-08: 최고 입찰이 아닌데 HELD인 확보
SELECT h.credit_hold_id
FROM credit_holds h
JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
WHERE h.status = 'HELD'
  AND b.bid_sequence < (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = b.auction_id);

-- INV-AU-14: 지갑의 묶인 금액과 HELD 합계 불일치
SELECT w.credit_wallet_id
FROM credit_wallets w
LEFT JOIN credit_holds h ON h.credit_wallet_id = w.credit_wallet_id AND h.status = 'HELD'
GROUP BY w.credit_wallet_id, w.reserved_amount
HAVING w.reserved_amount <> COALESCE(SUM(h.held_amount), 0);

-- INV-AU-15: 지갑 금액과 내역 합계 불일치
SELECT w.credit_wallet_id
FROM credit_wallets w
LEFT JOIN credit_transactions t ON t.credit_wallet_id = w.credit_wallet_id
GROUP BY w.credit_wallet_id, w.balance_amount, w.reserved_amount
HAVING w.balance_amount <> COALESCE(SUM(t.balance_delta), 0)
    OR w.reserved_amount <> COALESCE(SUM(t.reserved_delta), 0);
```

## AGENTS.md 핵심 불변식과의 관계

- "Auction closing must produce at most one winner." → INV-AU-02
- "Duplicate purchase requests must not create duplicate orders." → INV-AU-12

## 스키마 보강이 필요한 항목

아래 제약은 머지된 마이그레이션(`V20261111_1030`, `V20261111_1040`)에 없다. 1단계에서 새 마이그레이션으로 추가한다.

| 제약 | 관련 불변식 |
|---|---|
| `credit_wallets` CHECK `reserved_amount <= balance_amount` | INV-AU-06 |
| `auction_bids` UNIQUE를 `(request_key)`에서 `(bidder_user_id, request_key)`로 변경 | INV-AU-12 |
| `auction_results.winning_bid_id` NULL 허용, `result_type` 추가, `WON`이면 입찰 있음·`NO_BID`면 없음 CHECK | INV-AU-02 |
| `auctions` `extension_duration_seconds` CHECK 30 ~ 300 | 정책 4절 |
| `auctions.status`, `credit_holds.status`, `credit_transactions.transaction_type`, `auction_results.result_type` 값 CHECK | 상태 오기입 방지 |
| `auctions` 인덱스 `(status, starts_at)`, `(status, ends_at)` | 스케줄러 조회 |
