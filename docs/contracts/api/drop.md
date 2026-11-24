# Drop API 계약

> 관련 코드: `backend/src/main/java/com/restless/drop/api/`, `backend/src/main/java/com/restless/drop/application/dto/`, `backend/src/test/java/com/restless/drop/acceptance/`
>
> 마지막 확인: 2026-11-22 (#330 재고 버킷 분할. D1 등록과 D2 트랜잭션 문장, D2 품절 판정의 뜻을 고쳤다. 요청, 응답, 오류 코드는 그대로다. 2026-11-20 #285 상태 표기 갱신, 대기열 경로의 429를 #278 `application.yml`, `QueueRateLimitIntegrationTest`와 대조. 2026-11-19 #253 SKU별 입장 제어 정책의 계약 반영. D4는 #254 SKU별 입장 제어 구현과 대조했다. 나머지는 2026-11-14 기준: #92 등록 검증과 판매 상태, #41 탈퇴 제한, #97 탈퇴 동의, #105 검색과 정렬, #109 주문 상세 결제 대상)

- 상태: v1 IMPLEMENTED (2026-11-13 합의). 수용 테스트(`backend/src/test/java/com/restless/drop/acceptance/`)가 이 계약을 기준으로 판정한다.
- Base URL: `/api` ([ADR-0002](../../decisions/0002-common-conventions.md)). 오류는 공통 형식 `{code, message, details, traceId, timestamp}` ([common.md](common.md)).
- 회원 ID는 토큰(`@LoginMember`)에서만 받는다. 시각은 UTC ISO-8601.
- 용어: **재고 점유** = 결제 전에 SKU 재고 1개를 잠깐 확보하는 것 (상태 `RESERVED`, 테이블 `drop_reservations`).

## 단계별 범위

| 단계 | API |
|---|---|
| D1 상품 등록·조회 | 운영자 등록·게시, 사용자 목록·상세 |
| D2 재고 점유 + 주문 생성 | 재고 점유 생성·조회 |
| D3 결제 연결 | 재고 점유 취소, 결제는 공통 결제 API(`payableType = DROP`, `payableId = reservationId`) |
| D4 입장 제어 | SKU별 입장 신청, 내 입장 상태 |
| 탈퇴 제한 (#41) | 회원 탈퇴(`POST /api/me/withdraw`)를 막는 드롭 조건 |

호출 순서: 상품 상세 → (배송, 결제 페이지) → **결제하기 = 선택한 SKU의 입장 신청** → 대기 상태 반복 조회 → 입장 허가 → 재고 점유 + 주문 생성 → `POST /api/payments` → 토스 결제창 → `POST /api/payments/confirm` → 결과 조회

## D1. 상품 등록·조회

### `POST /api/admin/drops` (ADMIN)

```json
{
  "concertId": 1,
  "title": "후드티 드롭",
  "description": "한정판 후드티",
  "imageUrl": "https://example.com/hoodie.png",
  "opensAt": "2026-11-20T03:00:00Z",
  "closesAt": "2026-11-20T05:00:00Z",
  "variants": [
    { "skuCode": "HOODIE-M", "optionName": "M", "priceAmount": 39000, "totalQuantity": 100 }
  ]
}
```

- 201 `{ "dropId": 1, "status": "DRAFT" }`
- `opensAt`이 과거여도 등록할 수 있다 (게시하면 바로 판매 시작). 등록 직후 상태는 항상 `DRAFT`다.
- 각 SKU의 재고는 `total = available = totalQuantity`, `reserved = 0`, `sold = 0`으로 시작한다. 서버는 이 재고를 `min(설정 K, totalQuantity)`개의 재고 버킷으로 나눠 저장한다. SKU 수량은 버킷 합이다 ([ADR-0005](../../decisions/0005-drop-inventory-buckets.md)).
- 검증 400 `INVALID_REQUEST`: 필수값, `opensAt < closesAt`, `closesAt > 서버 시각`(이미 끝난 판매 기간 금지. 시작 시각은 지나도 됨), SKU 1개 이상, `skuCode` 중복 없음, `priceAmount >= 1`(0원 주문은 결제 준비가 `FREE_ORDER`로 거절하므로 등록에서 막는다, #92), `totalQuantity >= 1`.
- 없는 `concertId` 404 `NOT_FOUND`. 로그인 안 함 401, ADMIN 아님 403.

### `POST /api/admin/drops/{dropId}/publish` (ADMIN)

- DRAFT → SCHEDULED. 200 `{ "dropId": 1, "status": "SCHEDULED" }`. DRAFT가 아니면 409 `DROP_INVALID_STATE`.

### `GET /api/drops` (공개)

- 게시된 드롭만 (DRAFT, CANCELLED 제외). 상세의 `variants`는 등록 순서.
- 200 `{ "items": [ { "dropId", "title", "imageUrl", "opensAt", "closesAt", "saleStatus" } ] }`
- 검색과 정렬 (#105). 예: `GET /api/drops?q=후드&sort=NEWEST`
  - `q`: 제목에 이 글자가 들어 있는 드롭만 준다. 대소문자를 가리지 않는다. 앞뒤 공백은 지우고, 비어 있으면 검색하지 않는다. `%`, `_`는 글자 그대로 찾는다. 50자를 넘으면 400 `INVALID_REQUEST`다. 글자 수는 한글과 이모지 모두 한 글자로 센다.
  - `sort`: `OPENS_AT`가 기본값이다. 판매 시작 시각 오름차순, 같으면 `dropId` 오름차순이다. `NEWEST`는 등록 최신순이다. 등록 시각 내림차순, 같으면 `dropId` 내림차순이다. 값은 대문자 그대로 받는다. 빈 값을 포함해 그 밖의 값은 400 `INVALID_REQUEST`다.
  - 페이지는 없다. 조건에 맞는 게시 드롭을 모두 준다. 드롭은 운영자가 등록해 수가 적다. 판매 상태 필터도 없다.

### `GET /api/drops/{dropId}` (공개)

- 게시되지 않은 드롭은 404 `NOT_FOUND`.
- 200
```json
{
  "dropId": 1, "title": "후드티 드롭", "description": "한정판 후드티", "imageUrl": "https://...",
  "opensAt": "...", "closesAt": "...", "saleStatus": "ON_SALE",
  "variants": [ { "variantId": 10, "skuCode": "HOODIE-M", "optionName": "M", "priceAmount": 39000, "soldOut": false } ]
}
```
- `saleStatus`: 판매 판정(D2)과 같은 기준이다. 게시 상태가 판매 가능(`SCHEDULED`, `OPEN`)이 아니면(`CLOSED` 등) 시각과 관계없이 `ENDED`. 판매 가능 상태이면 서버 시각 기준 `UPCOMING`(시작 전) / `ON_SALE`(`opensAt <= now < closesAt`) / `ENDED`(종료 후) (#92).
- `soldOut`: 그 SKU의 구매 가능 수량(재고 버킷 `available` 합) = 0. 재고 점유 중인 수량은 구매 가능 수량이 아니다. "현재 구매 불가"라는 뜻이며 최종 매진이 아니다. 재고가 반환되면 다시 `false`가 된다.

## D2. 재고 점유 + 주문 생성

### `POST /api/drops/{dropId}/reservations` (회원) + 헤더 `Idempotency-Key` (1~100자)

```json
{ "variantId": 10, "recipient": "홍길동", "phone": "010-1234-5678", "address": "서울시 강남구 1" }
```

- 수량 필드는 없다. 주문 하나 = SKU 한 종류, 수량 1 (INV-04).
- 판매 가능 = 게시됨(`SCHEDULED` 또는 `OPEN`)이고 `opensAt <= 서버 시각 < closesAt`. `OPEN` 전환은 판정에 영향이 없다. `CLOSED`·`CANCELLED`는 시각과 관계없이 판매하지 않는다 (#92).
- 하나의 트랜잭션: 판매 기간과 상태 확인 → 구매 슬롯 점유(INV-05) → 공통 주문(PENDING_PAYMENT, 금액 = 서버 가격, 배송지 스냅샷) 생성 → 재고 점유(RESERVED, 기한 = 지금 + TTL) 저장 → 그 SKU의 재고 버킷 하나에서 조건부 차감(`available - 1`, `reserved + 1`). 재고 점유가 주문을 NOT NULL FK로 가리켜서 주문을 먼저 만든다. 재고 차감은 재고 락을 짧게 쥐려고 마지막에 하고, 재고가 없거나 락 대기 중 판매가 끝나면 앞의 쓰기가 모두 롤백된다 (#322). 서버는 잠기지 않은 버킷을 고르고, 점유가 가져간 버킷을 기록한다 (#330).
- 201
```json
{ "reservationId": 5, "orderId": 7, "status": "RESERVED", "amount": 39000, "expiresAt": "..." }
```
- 같은 회원·드롭·`Idempotency-Key` 재요청: 200, 같은 `reservationId`·`orderId` (INV-07).
- 오류:
  - 404 `NOT_FOUND`: 드롭·SKU 없음, 게시되지 않음, SKU가 그 드롭 소속이 아님
  - 409 `DROP_NOT_ON_SALE`: 판매 시작 전, 종료 후 (INV-14)
  - 409 `DROP_SOLD_OUT`: 그 SKU 구매 가능 수량 0. 판정 시점에 모든 재고 버킷의 커밋된 구매 가능 수량이 0이었다는 뜻이다. 그 직후 반환이 커밋되면 곧바로 다시 살 수 있다. 버킷 분할 전에도 같은 창이 있었다 (2026-11-22 결정 D3)
  - 409 `DROP_ACTIVE_PURCHASE_EXISTS`: 같은 회원이 같은 드롭에서 진행 중인 거래가 있음 (INV-05)
  - 403 `DROP_ADMISSION_REQUIRED`: 입장 제어 사용 시 유효한 입장 허가 없음 (D4, INV-06). 종료된 입장 허가, 요청 `variantId`와 다른 SKU의 입장 허가도 같은 오류로 거절한다 (2026-11-19 결정. 정상 화면에서는 생기지 않는 요청을 막는 방어 검사다)
  - 400 `INVALID_REQUEST`: 키·배송지 검증
- TTL 설정 `restless.drop.reservation.ttl` (기본 5분).
- 입장 제어 사용 여부 설정 `restless.drop.queue.required` (기본 `true`). `false`면 입장 허가 없이 재고 점유한다. 트래픽 실험 EXP-02(입장 제어 켬/끔 비교)와 D2 수용 테스트가 쓴다.

### `GET /api/drops/{dropId}/reservations/{reservationId}` (본인)

- 200 `{ "reservationId", "orderId", "variantId", "status", "amount", "expiresAt" }`. 남의 것이나 없는 것은 404.

## D3. 결제 연결

- 결제 준비: `POST /api/payments` `{ "payableType": "DROP", "payableId": <reservationId> }` ([common.md](common.md)).
- 주문 상세 `GET /api/orders/{orderId}`의 `payable`과 `payableExpiresAt`은 드롭이 알려 준다. 결제 대상은 그 주문의 재고 점유이고, 기한은 점유 만료 시각이다 (`DropOrderPayableResolver`, #109).
- 드롭 `PaymentResultHandler`는 [transactions.md](../../domains/drop/transactions.md) TX2~TX5와 [policy.md](../../domains/drop/policy.md) 8·10절을 따른다 (재결제 허용, 환불 종류별 재고 처리). 환불 요청은 받아들인다(전액 환불 지원).
- 결제창에 보이는 주문명은 드롭 제목이다. 100자를 넘으면 100자로 자른다. 금액은 주문 스냅샷 금액이다.
- 만료 작업 빈 이름 `dropReservationExpiryJob`, 메서드 `runOnce()`. RESERVED이고 기한이 지난 재고 점유를 EXPIRED로 닫는다. 설정 `restless.drop.expiry.enabled=false`는 **주기 실행만 멈추고 빈은 남는다** (수용 테스트가 직접 호출).

### `POST /api/drops/{dropId}/reservations/{reservationId}/cancel` (본인)

- RESERVED일 때만. 재고 반환, 주문 CANCELLED, 구매 슬롯 해제. 200 `{ "reservationId", "status": "RELEASED" }`.
- RESERVED가 아니면 409 `DROP_INVALID_STATE`. 결제 승인을 시작한 뒤(CONFIRMING 이후)뿐 아니라 이미 취소(RELEASED)되거나 만료(EXPIRED)된 재고 점유를 다시 취소해도 409다.

## D4. 입장 제어

> SKU별 구매 입장 정책(#253)을 구현했다(#254). 수용 테스트 `DropQueueAcceptanceTest`와 `DropSkuQueuePolicyAcceptanceTest`가 계약을 검증한다.

### 현재 구매 불가

SKU의 `available = 0`이면 그 SKU는 **현재 구매 불가**다 (`reserved`와 무관). 신규 진입을 거절하고 입장 허가로 전이하지 않으며 기존 `WAITING`과 미사용 `ADMITTED`를 종료한다. 재고가 반환되면 판매 기간 안에서 새 진입을 다시 받는다. 종료된 입장 순번과 입장 허가는 복구하지 않는다. 화면은 "현재 구매 불가"로 안내하고 최종 매진이라고 단정하지 않는다. 드롭 상세의 `soldOut`도 같은 뜻이다.

### `POST /api/drops/{dropId}/queue/entries` (회원)

- 요청: `{ "variantId": 10 }`. 진입 때 SKU를 고르고 서버가 `variant_id`로 저장한다.
- 입장 신청을 한다. 이미 입장 대기 중이면 같은 대기를 돌려준다(1건).
- 201 신규 / 200 기존 `{ "status": "WAITING", "variantId": 10, "position": 12 }`. 이미 입장 허가를 받은 상태면 200 `{ "status": "ADMITTED", "variantId": 10, "admittedUntil": "..." }`.
- 응답: 모든 응답에 `variantId`를 포함한다. `position`은 같은 SKU의 `WAITING` 안에서의 입장 순번이다. 다른 SKU의 대기는 입장 순번에 영향을 주지 않는다.
- 진입 재요청은 heartbeat를 늘리지 않는다 (화면 신호는 아래 상태 조회).
- 이전 대기가 `UNAVAILABLE` 등으로 끝났으면 새 행을 만들어 201과 새 입장 순번을 준다. 종료 전 입장 순번을 우대하지 않는다.
- 없는 드롭 404 `NOT_FOUND`. 판매 기간이 아니거나 게시되지 않은 드롭(DRAFT 등)이면 409 `DROP_NOT_ON_SALE`.
- 오류:
  - 400 `INVALID_REQUEST`: `variantId` 없음
  - 404 `NOT_FOUND`: 그 드롭 소속이 아닌 SKU, 없는 SKU
  - 409 `DROP_SOLD_OUT`: 그 SKU의 `available = 0`(현재 구매 불가). 기존 코드를 재사용하고 메시지는 "현재 구매 불가"다. 행을 만들지 않는다.
  - 429 `TOO_MANY_REQUESTS`: 같은 회원이 10초에 5회를 넘겨 진입 요청 ([요청 횟수 제한](common.md#요청-횟수-제한))
- 같은 회원이 같은 드롭에서 다른 SKU로 진입하면 201과 함께 새 SKU의 새 `WAITING`(새 입장 순번)을 만들고 기존 SKU의 대기를 뺀다. 기존 대기가 `ADMITTED`여도 같다. 새 SKU가 `available = 0`이면 409 `DROP_SOLD_OUT`이고 기존 대기는 그대로다. 같은 SKU로 다시 진입하면 기존 대기를 돌려준다.
- 페이지를 나가면 마지막 유효 조회 후 30~40초 사이에 이탈로 본다. 별도 해제 API는 없다.

### `GET /api/drops/{dropId}/queue/me` (회원)

- 200 `{ "status": "WAITING" | "ADMITTED" | "EXPIRED" | "CONSUMED" | "UNAVAILABLE", "position": 3, "admittedUntil": "..." }` (`position`은 WAITING일 때만, `admittedUntil`은 ADMITTED일 때만). `variantId`도 함께 준다.
- `UNAVAILABLE`: 그 SKU가 현재 구매 불가가 되어 대기나 미사용 입장 허가가 종료됐다. `{ "status": "UNAVAILABLE", "variantId": 10 }`. 404가 아니다. 화면이 "현재 구매 불가"를 보여 줄 수 있어야 하기 때문이다. 사용자가 다시 진입하면 새 기록이 최신이 된다.
- 대기 중 상태 조회는 "아직 화면에 있음" 신호다. 저장된 heartbeat가 `restless.drop.queue.heartbeat-timeout`보다 오래되면 404를 반환한다. 다시 들어오면 맨 뒤에서 다시 시작한다.
  - WAITING 조회는 마지막 저장 후 10초를 넘겼을 때 heartbeat를 기록한다. 이탈 기준은 저장 시각에서 40초 초과다. `ADMITTED`에는 적용하지 않는다.
  - 상태 조회 간격 3~4초는 클라이언트 제안값이다. 계약상 의무도, 확정 정책도 아니다.
- 대기 기록이 없거나 최신 기록이 CLOSED(이탈, 판매 종료)면 404. EXPIRED, CONSUMED, UNAVAILABLE은 200으로 그대로 보여 준다.
- 이 요청은 어떤 경우에도 즉시 응답한다. 입장이 재개될 때까지 요청을 붙잡지 않는다.
- 같은 회원이 1초에 5회를 넘겨 조회하면 429 `TOO_MANY_REQUESTS`다. 429로 거절된 조회는 heartbeat를 갱신하지 않는다 ([요청 횟수 제한](common.md#요청-횟수-제한)).
- `available = 0`이 되어도 `WAITING`이 곧바로 `UNAVAILABLE`로 바뀌는 것은 아니다. 입장 허가 작업이 관찰한 뒤 바뀐다. 그 사이는 입장 허가 작업 한 번의 주기 안이며, 그동안에도 입장 허가 없이는 점유할 수 없다.

### 입장 허가

- 작업 빈 이름 `dropQueueAdmissionJob`, 메서드 `runOnce()`. 설정 `restless.drop.queue.scheduler.enabled=false`는 주기 실행만 멈추고 빈은 남는다. 한 번 실행에 살아 있는 대기가 있는 SKU마다 `restless.drop.queue.admit-per-run`명까지 대략적인 진입 순서로 ADMITTED로 바꾼다. 정확한 선착순은 보장하지 않는다(처리 속도 우선).
  - SKU마다 `min(max(0, available - 미사용 유효 ADMITTED 수), admit-per-run)`명을 같은 SKU의 입장 순번(`id`)대로 입장시킨다. 같은 SKU를 여러 작업이 동시에 처리해도 입장 허가가 `available`을 넘기지 않는다 (INV-16).
  - SKU의 `available = 0`이면 그 SKU는 입장시키지 않고 `WAITING`과 미사용 `ADMITTED`를 종료한다 (INV-17). `reserved` 수량은 보지 않는다. 다른 SKU는 영향이 없다.
  - 재고가 반환돼 `available > 0`이 되면 새로 진입한 사용자부터 같은 SKU의 입장 순번대로 입장시킨다. 종료된 입장 순번과 입장 허가는 복구하지 않는다.
- 기본값: `heartbeat-timeout` 40초, `admit-per-run` 100명, 입장 허가 작업 주기 1초(`restless.drop.queue.scheduler.fixed-delay-ms`).
- 입장 허가 유효 시간 `restless.drop.queue.admission-ttl` (기본 5분). 재고 점유에 성공하면 CONSUMED.
- 입장은 재고를 보장하지 않는다. 입장 후에도 품절이면 409 `DROP_SOLD_OUT`. TX1 조건부 재고 UPDATE가 재고 확보의 최종 검증이다.
- 입장 허가는 해당 SKU의 재고 점유에만 쓴다. 종료된 입장 허가로는 점유할 수 없다 (403 `DROP_ADMISSION_REQUIRED`).
- 대기 종료는 이미 성공한 점유를 취소하지 않는다. 결제, 기한, 재결제, 복구는 D3와 policy.md 8절을 그대로 따른다.
- PG 결과가 불명확한 점유는 `reserved`로 남는다. 이를 실패로 보고 재고를 반환하거나 입장시키지 않는다.

## 회원 탈퇴 제한 (#41)

API는 회원 모듈의 `POST /api/me/withdraw`다. 드롭은 `WithdrawalGuard`(`member/application/WithdrawalGuard.java`) 구현으로 아래 조건에서 탈퇴를 막는다. 막으면 409 `MEMBER_HAS_ACTIVE_PARTICIPATION`이고 회원 상태는 바뀌지 않는다.

| 막는다 | 이유 |
|---|---|
| 본인의 재고 점유가 `RESERVED`, `CONFIRMING`, `RECONCILING`, `COMPENSATING` | 재고를 잡고 있거나 결제 결과를 기다린다 |
| 본인의 드롭 주문이 `PAYMENT_REVIEW`, `REFUND_PENDING`, `REFUND_REVIEW` | 돈의 행방이 정해지지 않았다 |

막지 않는다: 점유 `CONFIRMED`(주문 `PAID`), `EXPIRED`, `RELEASED`, `RESTORED`, 주문 `PAID`, `CANCELLED`, `REFUNDED`. 실제 배송이 없으므로 결제가 끝난 구매는 탈퇴를 막지 않는다. 대신 탈퇴 요청에 결제 완료 주문 안내 동의를 받는다 (policy.md 11절, [공통 API](common.md) 탈퇴, #97). 다른 회원의 거래는 상관없다.

## 운영자 API (v1 범위 밖)

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/admin/drops` | 전체 드롭 목록 |
| GET | `/api/admin/drops/{dropId}` | 운영자 상세 |
| PATCH | `/api/admin/drops/{dropId}` | 상품 설정 수정 |
| GET | `/api/admin/drops/{dropId}/summary` | 재고, 판매 현황 |
