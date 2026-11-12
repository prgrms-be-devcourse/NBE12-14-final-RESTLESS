# Auction 상태 머신

## 1. 경매 (`auctions.status`)

```text
SCHEDULED → OPEN → CLOSED
SCHEDULED → CLOSED      (스케줄러가 시작 처리를 놓친 채 종료 시각이 지난 경우)
SCHEDULED → (삭제)      (시작 전 삭제. 행이 사라진다)
```

| 전이 | 누가 | 조건 |
|---|---|---|
| 등록 → `SCHEDULED` | 주최자, ADMIN | 시작 시각이 등록 시점보다 뒤 |
| `SCHEDULED` → `OPEN` | 시작 스케줄러 | `starts_at <= 지금` |
| `OPEN` → `CLOSED` | 종료 스케줄러 | 경매 행 잠금 뒤 `ends_at <= 지금`을 다시 확인 |
| `SCHEDULED` → 삭제 | 주최자 본인, ADMIN | 잠금 뒤 `지금 < starts_at` |

- **입찰 가능 여부는 상태가 아니라 시각(`starts_at <= 지금 < ends_at`)으로 판단한다.** 스케줄러가 늦어도 마감 뒤 입찰은 거절된다.
- `CLOSED`가 되면 결과 행이 반드시 있다 (아래 3절).
- 1차에는 진행 중 취소(`CANCELLED`)가 없다.

## 2. 입찰 (저장하지 않고 계산)

`auction_bids`에는 상태 컬럼이 없다. 화면과 API의 입찰 상태는 아래 규칙으로 계산한다.

```text
HIGHEST → OUTBID      (다른 회원의 더 높은 입찰이 수락됨)
HIGHEST → WON         (최고 입찰인 채로 경매 종료)
```

| 상태 | 계산 규칙 |
|---|---|
| `HIGHEST` | 경매가 `CLOSED`가 아니고, 그 경매에서 `bid_sequence`가 가장 크다 |
| `OUTBID` | 그 경매에 `bid_sequence`가 더 큰 입찰이 있다 (확보는 `RELEASED`) |
| `WON` | `auction_results.winning_bid_id` = 이 입찰 |

같은 회원이 한 경매에 여러 번 입찰할 수 있다 (추월당한 뒤 다시 입찰). 내 입찰 조회(`GET /api/me/bids`)는 경매별로 내 마지막 입찰의 상태를 보여 주며, 종료된 경매에서 낙찰되지 못한 경우는 `OUTBID` 대신 `LOST`로 표시한다.

## 3. 경매 결과 (`auction_results.result_type`)

| 값 | 뜻 | `winning_bid_id` |
|---|---|---|
| `WON` | 최고 입찰자가 낙찰 | 낙찰 입찰 ID (필수) |
| `NO_BID` | 입찰 없이 종료, 미낙찰 | NULL |

결과는 경매가 `CLOSED`가 될 때 한 번만 만들어지고 바뀌지 않는다.

## 4. 크레딧 확보 (`credit_holds.status`)

```text
HELD → RELEASED       (해당 입찰이 추월됨)
HELD → CAPTURED       (해당 입찰이 낙찰됨, 보유액에서 차감)
```

`RELEASED`, `CAPTURED`는 끝 상태다. 입찰 하나에 확보는 최대 하나다 (`UNIQUE(auction_bid_id)`).

## 5. 크레딧 내역 (`credit_transactions.transaction_type`)

| 값 | 사건 | `balance_delta` | `reserved_delta` | `credit_hold_id` |
|---|---|---|---|---|
| `GRANT` | 지급 | + 금액 | 0 | NULL |
| `HOLD` | 입찰 수락 | 0 | + 금액 | 확보 ID |
| `RELEASE` | 추월 | 0 | − 금액 | 확보 ID |
| `CAPTURE` | 낙찰 | − 금액 | − 금액 | 확보 ID |

## 6. 상태 조합

| 경매 | 결과 | 최고 입찰의 확보 | 나머지 입찰의 확보 |
|---|---|---|---|
| `SCHEDULED` | 없음 | 입찰 없음 | 입찰 없음 |
| `OPEN` | 없음 | `HELD` (입찰이 있으면) | 모두 `RELEASED` |
| `CLOSED` | `WON` | `CAPTURED` | 모두 `RELEASED` |
| `CLOSED` | `NO_BID` | 입찰 없음 | 입찰 없음 |

이 표에 없는 조합은 버그다. 동시성 테스트 끝에 확인한다 ([invariants.md](invariants.md)).
