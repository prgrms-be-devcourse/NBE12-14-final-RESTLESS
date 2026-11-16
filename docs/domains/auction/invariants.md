# Auction 불변식

> 관련 코드: `backend/src/main/java/com/restless/auction/domain/`, `backend/src/main/java/com/restless/credit/domain/`, `backend/src/main/resources/db/migration/V*__auction_*.sql`, `backend/src/test/resources/harness/auction-invariants.sql`
>
> 마지막 확인: 2026-11-15 (#140 종료 실패 테스트, 불변식 SQL 파일 분리와 함께 고침)

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

## 확인 방법

| 불변식 | 확인 방법 |
|---|---|
| INV-AU-01, 03, 04, 05 | 동시 입찰 테스트: 같은 금액 N건 동시 요청 시 1건만 수락, 판매자·최고 입찰자·금액 부족 요청은 거절되고 아무것도 저장되지 않음 |
| INV-AU-02 | 종료를 동시에 여러 번 실행해도 결과 행 1건. 결과 행 직접 중복 INSERT가 PK에서 거부됨 |
| INV-AU-06 | 한 회원이 여러 경매에 동시 입찰해 사용 가능액을 넘기는 테스트. `reserved > balance` 직접 UPDATE가 CHECK에서 거부됨 |
| INV-AU-07, 08, 14, 15 | 불변식 SQL (아래 절) |
| INV-AU-09 | SSE 연결을 끊은 뒤에도 입찰·확보가 그대로인지 확인 (`AuctionStreamTest`) |
| INV-AU-10, 11 | 종료 직전 입찰과 종료 스케줄러 경합 테스트. `ends_at`이 줄어들지 않고, `ends_at` 이후 `accepted_at`인 입찰이 없음 |
| INV-AU-12 | 같은 키 동시 재전송 시 입찰·확보·내역·연장이 1번씩만 반영 |
| INV-AU-13 | 차감 뒤 결과 저장에서 예외를 넣어 차감·결과·종료가 함께 롤백되고, 다시 종료하면 한 번만 차감되는지 확인 (`AuctionCloseFailureTest.차감_뒤_결과_저장에서_실패하면_차감까지_함께_롤백되고_다시_종료하면_한_번만_차감된다_INV_AU_13`). 정상 종료 뒤에는 `SOLD` 결과의 낙찰 입찰이 마지막 입찰이고 그 확보가 `CAPTURED`인지 `AuctionFixtures.assertInvariants`가 확인한다 |

## 불변식 SQL

쿼리는 [`backend/src/test/resources/harness/auction-invariants.sql`](../../../backend/src/test/resources/harness/auction-invariants.sql) 한 곳에만 둔다. 쿼리마다 불변식을 어긴 행의 ID를 돌려주고, 0행이면 지켜진 것이다.

| 블록 | 확인하는 것 |
|---|---|
| INV-AU-07, 08 | 최고 입찰만 `HELD`이고, 추월된 입찰의 확보는 해제돼 있다 |
| INV-AU-14, 15 | 지갑의 묶인 금액 = `HELD` 합계, 지갑 금액 = 내역 합계 |
| INV-AU-03 | 다음 입찰은 이전 입찰 + 최소 인상액 이상 |
| INV-AU-11 | 종료된 경매에 종료 시각 이후 수락된 입찰이 없다 |
| INV-AU-13 | `SOLD`는 마지막 입찰이고 그 확보는 `CAPTURED` |
| 상태 조합 ([state-machines.md](state-machines.md) 6절) | `CLOSED`와 결과 행은 함께 있다, 종료된 경매에 `HELD`가 없다, `UNSOLD`에는 입찰이 없다 |

- **테스트:** 데이터를 바꾸는 경매 테스트는 끝에 `AuctionFixtures.assertInvariants()`로 모든 블록을 실행한다.
- **부하 테스트 뒤 점검:** 같은 파일을 MySQL에서 그대로 실행해 모든 쿼리가 0행인지 본다.
- 불변식을 추가하면 `-- name: <이름>` 블록을 하나 더 붙인다. 테스트는 자동으로 새 블록도 실행한다.

## AGENTS.md 핵심 불변식과의 관계

- "Auction closing must produce at most one winner." → INV-AU-02
- "Duplicate purchase requests must not create duplicate orders." → INV-AU-12

## 스키마 보강 항목 (반영됨)

아래 제약은 처음 머지된 마이그레이션(`V20261111_1030`, `V20261111_1040`)에 없어서 `V20261112_1045__auction_alter_tables.sql`로 추가했다. 위반하면 DB가 거부하는지는 `AuctionSchemaConstraintTest`가 확인한다.

| 제약 | 관련 불변식 |
|---|---|
| `credit_wallets` CHECK `reserved_amount <= balance_amount` | INV-AU-06 |
| `auction_bids` UNIQUE를 `(request_key)`에서 `(bidder_user_id, request_key)`로 변경 | INV-AU-12 |
| `auction_results.winning_bid_id` NULL 허용, `result_type` 추가, `SOLD`면 입찰 있음·`UNSOLD`면 없음 CHECK | INV-AU-02 |
| `auctions` `extension_duration_seconds` CHECK 30 ~ 300 | 정책 4절 |
| `auctions.status`, `credit_holds.status`, `credit_transactions.transaction_type`, `auction_results.result_type` 값 CHECK | 상태 오기입 방지 |
| `auctions` 인덱스 `(status, starts_at)`, `(status, ends_at)` | 스케줄러 조회 |

보강하지 않은 차이: `ck_auctions_prices`는 `starting_price_amount >= 0`이라 정책(시작가 1 이상, policy.md 4절)보다 느슨하다. 1 이상은 요청 검증과 `Auction` 생성에서 막는다.
