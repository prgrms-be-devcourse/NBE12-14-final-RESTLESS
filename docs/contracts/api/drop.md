# Drop API 계약

> 관련 코드: `backend/src/main/java/com/restless/drop/api/`, `backend/src/main/java/com/restless/drop/application/dto/`, `backend/src/test/java/com/restless/drop/acceptance/`
>
> 마지막 확인: 2026-11-25 (#423 진입 503의 `Retry-After` 기본값 3초에서 5초. 필드와 오류 코드는 그대로다). 이전: 2026-11-24 (#390 대기열 응답의 선택 필드 `pollAfterMs`, 동시 처리 상한 초과 503 `DROP_QUEUE_BUSY`. 기존 필드와 오류 코드는 그대로다. 이전: 2026-11-22 (#330 재고 버킷 분할. D1 등록과 D2 트랜잭션 문장, D2 품절 판정의 뜻을 고쳤다. 요청, 응답, 오류 코드는 그대로다. 2026-11-20 #285 상태 표기 갱신, 대기열 경로의 429를 #278 `application.yml`, `QueueRateLimitIntegrationTest`와 대조. 2026-11-19 #253 SKU별 입장 제어 정책의 계약 반영. D4는 #254 SKU별 입장 제어 구현과 대조했다. 나머지는 2026-11-14 기준: #92 등록 검증과 판매 상태, #41 탈퇴 제한, #97 탈퇴 동의, #105 검색과 정렬, #109 주문 상세 결제 대상)

- 상태: v1 IMPLEMENTED (2026-11-13 합의). 수용 테스트(`backend/src/test/java/com/restless/drop/acceptance/`)가 이 계약을 기준으로 판정한다.
- Base URL: `/api` ([ADR-0002](../../decisions/0002-common-conventions.md)). 오류는 공통 형식 `{code, message, details, traceId, timestamp}` ([common.md](common.md)).
- 회원 ID는 토큰(`@LoginMember`)에서만 받는다. 시각은 UTC ISO-8601.
- 용어: **재고 점유** = 결제 전에 SKU 재고 1개를 잠깐 확보하는 것 (상태 `RESERVED`, 테이블 `drop_reservations`).

## 단계별 범위

| 단계 | API |
|---|---|
| D1 상품 등록·조회 | 운영자 등록·게시, 주최자 등록·게시·목록 (#429), 사용자 목록·상세 |
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
- 없는 `concertId`이거나 비공개(공개 전) 공연이면 404 `NOT_FOUND`. 드롭은 공개된 공연에만 연결한다([주최자 정책](../../domains/ticketing/organizer-policy.md)). 로그인 안 함 401, ADMIN 아님 403.

### `POST /api/admin/drops/{dropId}/publish` (ADMIN)

- DRAFT → SCHEDULED. 200 `{ "dropId": 1, "status": "SCHEDULED" }`. DRAFT가 아니면 409 `DROP_INVALID_STATE`.

### 주최자 드롭 API (ORGANIZER, ADMIN, #429)

| Method | Endpoint | 기능 |
|---|---|---|
| POST | `/api/organizer/drops` | 드롭 등록. 요청, 응답, 검증은 `POST /api/admin/drops`와 같다 |
| POST | `/api/organizer/drops/{dropId}/publish` | 게시. 응답과 상태 규칙은 관리자 게시와 같다 |
| GET | `/api/organizer/drops` | 관리할 수 있는 드롭 목록 |
| DELETE | `/api/organizer/drops/{dropId}` | 드롭 삭제 (#430) |

- 주최자는 본인 공연에만 드롭을 등록한다. 관리자는 모든 공연에 등록한다. 드롭의 주최자는 등록 요청자가 아니라 공연의 주최자다.
- 다른 주최자의 공연과 없는 공연은 모두 404 `NOT_FOUND`다. 다른 주최자의 드롭 게시도 404다. 다른 주최자의 상품이 있는지 알려 주지 않는다.
- `POST /api/admin/drops`로 등록한 드롭은 주최자가 없다. 관리자만 관리하고 주최자 목록에도 나오지 않는다.
- 목록: 주최자는 본인 드롭, 관리자는 전체 드롭을 등록 최신순(`createdAt`, `dropId` 내림차순)으로 받는다. 게시 전 드롭도 포함한다. 페이지는 없다.
- 200 `{ "items": [ { "dropId", "concertId", "organizerId", "title", "status", "opensAt", "closesAt", "createdAt" } ] }`. 주최자가 없는 드롭의 `organizerId`는 `null`이다.
- 로그인 안 함 401, ORGANIZER나 ADMIN이 아니면 403, 탈퇴한 회원은 404 `MEMBER_NOT_FOUND`.

#### 삭제 (#430)

- 200 `{ "dropId": 1, "status": "CANCELLED" }`. 판매 중이어도 삭제할 수 있다. 이미 삭제한 드롭은 409 `DROP_INVALID_STATE`, 다른 주최자의 드롭은 404다.
- 삭제하면 목록, 상세, 새 재고 점유에서 빠진다. 삭제 뒤 결제 승인을 시작하는 요청은 거절되어 돈이 나가지 않는다.
- 결제를 마친 구매는 삭제 즉시 환불하지 않는다. 매일 00:30(KST) 배치가 손님 환불과 같은 경로로 전액 환불하고 08:00(KST)에 멈춘다. 남은 건은 다음 날 이어서 한다.
- 배치는 그날 실행을 시작하기 전에 삭제된 드롭만 처리한다. 배치가 도는 중에 삭제한 드롭은 다음 날 00:30에 처리한다 (#435).
- 회원이 직접 요청한 환불은 이 배치와 상관없이 즉시 처리한다. 결제는 시간 제한 없이 언제든 할 수 있다.
- 삭제 직전에 승인이 이미 시작된 결제는 승인될 수 있다. 그 구매도 다음 배치에서 환불한다. 결과가 불명확한 결제는 확정된 뒤 환불한다.

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
- 201 신규 / 200 기존 `{ "status": "WAITING", "variantId": 10, "position": 12, "pollAfterMs": 1000 }`. 이미 입장 허가를 받은 상태면 200 `{ "status": "ADMITTED", "variantId": 10, "admittedUntil": "..." }`.
- 응답: 모든 응답에 `variantId`를 포함한다. `position`은 같은 SKU의 `WAITING` 안에서의 입장 순번이다. 다른 SKU의 대기는 입장 순번에 영향을 주지 않는다.
- 진입 재요청은 heartbeat를 늘리지 않는다 (화면 신호는 아래 상태 조회).
- 이전 대기가 `UNAVAILABLE` 등으로 끝났으면 새 행을 만들어 201과 새 입장 순번을 준다. 종료 전 입장 순번을 우대하지 않는다.
- 없는 드롭 404 `NOT_FOUND`. 판매 기간이 아니거나 게시되지 않은 드롭(DRAFT 등)이면 409 `DROP_NOT_ON_SALE`.
- 오류:
  - 400 `INVALID_REQUEST`: `variantId` 없음
  - 404 `NOT_FOUND`: 그 드롭 소속이 아닌 SKU, 없는 SKU
  - 409 `DROP_SOLD_OUT`: 그 SKU의 `available = 0`(현재 구매 불가). 기존 코드를 재사용하고 메시지는 "현재 구매 불가"다. 행을 만들지 않는다.
  - 429 `TOO_MANY_REQUESTS`: 같은 회원이 10초에 5회를 넘겨 진입 요청 ([요청 횟수 제한](common.md#요청-횟수-제한))
  - 503 `DROP_QUEUE_BUSY`: 이 서버가 동시에 처리하는 진입 요청이 상한을 넘음 ([동시 처리 상한](#대기열-동시-처리-상한)). 행을 만들지 않는다. 같은 요청을 `Retry-After` 뒤 다시 보내도 안전하다.
- 같은 회원이 같은 드롭에서 다른 SKU로 진입하면 201과 함께 새 SKU의 새 `WAITING`(새 입장 순번)을 만들고 기존 SKU의 대기를 뺀다. 기존 대기가 `ADMITTED`여도 같다. 새 SKU가 `available = 0`이면 409 `DROP_SOLD_OUT`이고 기존 대기는 그대로다. 같은 SKU로 다시 진입하면 기존 대기를 돌려준다.
- 페이지를 나가면 마지막 유효 조회 후 30~40초 사이에 이탈로 본다. 별도 해제 API는 없다.

### `GET /api/drops/{dropId}/queue/me` (회원)

- 200 `{ "status": "WAITING" | "ADMITTED" | "EXPIRED" | "CONSUMED" | "UNAVAILABLE", "position": 3, "admittedUntil": "...", "pollAfterMs": 1000 }` (`position`과 `pollAfterMs`는 WAITING일 때만, `admittedUntil`은 ADMITTED일 때만). `variantId`도 함께 준다.
- `UNAVAILABLE`: 그 SKU가 현재 구매 불가가 되어 대기나 미사용 입장 허가가 종료됐다. `{ "status": "UNAVAILABLE", "variantId": 10 }`. 404가 아니다. 화면이 "현재 구매 불가"를 보여 줄 수 있어야 하기 때문이다. 사용자가 다시 진입하면 새 기록이 최신이 된다.
- 대기 중 상태 조회는 "아직 화면에 있음" 신호다. 저장된 heartbeat가 `restless.drop.queue.heartbeat-timeout`보다 오래되면 404를 반환한다. 다시 들어오면 맨 뒤에서 다시 시작한다.
  - WAITING 조회는 마지막 저장 후 10초를 넘겼을 때 heartbeat를 기록한다. 이탈 기준은 저장 시각에서 40초 초과다. `ADMITTED`에는 적용하지 않는다.
  - 다음 조회 간격은 서버가 `pollAfterMs`로 알려 준다 ([조회 간격](#조회-간격-pollafterms)). 클라이언트는 `pollAfterMs`보다 자주 조회하지 않는다.
- 대기 기록이 없거나 최신 기록이 CLOSED(이탈, 판매 종료)면 404. EXPIRED, CONSUMED, UNAVAILABLE은 200으로 그대로 보여 준다.
- 이 요청은 어떤 경우에도 즉시 응답한다. 입장이 재개될 때까지 요청을 붙잡지 않는다.
- 같은 회원이 1초에 5회를 넘겨 조회하면 429 `TOO_MANY_REQUESTS`다. 429로 거절된 조회는 heartbeat를 갱신하지 않는다 ([요청 횟수 제한](common.md#요청-횟수-제한)).
- 이 서버가 동시에 처리하는 상태 조회가 상한을 넘으면 503 `DROP_QUEUE_BUSY`다. 503으로 거절된 조회도 heartbeat를 갱신하지 않는다 ([동시 처리 상한](#대기열-동시-처리-상한)).
- `available = 0`이 되어도 `WAITING`이 곧바로 `UNAVAILABLE`로 바뀌는 것은 아니다. 입장 허가 작업이 관찰한 뒤 바뀐다. 그 사이는 입장 허가 작업 한 번의 주기 안이며, 그동안에도 입장 허가 없이는 점유할 수 없다.

### 조회 간격 (`pollAfterMs`)

2026-11-24 사용자 위임 결정이다 (#390, 근거는 이슈의 측정 기록). 서버가 WAITING 응답에 다음 조회까지 기다릴 시간을 밀리초로 싣는다. 선택 필드이며 기존 필드는 그대로다.

| 입장 순번 (`position`) | `pollAfterMs` |
|---|---|
| 1 ~ 20 | 1000 |
| 21 ~ 200 | 2000 |
| 201 ~ 1000 | 3000 |
| 1001 이상 | 5000 |

- 진입 응답과 상태 조회 응답의 WAITING에만 있다. ADMITTED, EXPIRED, CONSUMED, UNAVAILABLE에는 없다. 이 상태들은 대기가 끝났으므로 조회 간격이 뜻을 갖지 않는다.
- 클라이언트는 `pollAfterMs`보다 자주 조회하지 않는다. 필드가 없으면 클라이언트가 정한 간격을 쓴다. 의무는 아니지만 따르지 않아도 사용자별 요청 횟수 제한(429)은 그대로 적용된다.
- 구간 경계와 값은 `restless.drop.queue.poll.tiers`, `fallback-ms`로 바꾼다. 어떤 값도 `restless.drop.queue.poll.max-ms`(기본은 10초, heartbeat 기록 간격, 이탈 판정 시간의 3분의 1 중 가장 작은 값)를 넘지 않는다.
- 설정은 기동 때 검증한다. `max-ms`가 heartbeat 기록 간격(기본 10초)보다 크거나, 이탈 판정 시간(기본 40초) 안에 3번 미만 들어오게 하면 기동이 실패한다. 구간은 순번이 늘고 간격이 줄지 않아야 한다.

### 대기열 동시 처리 상한

2026-11-24 사용자 위임 결정이다 (#390). 서버 한 대가 동시에 처리하는 대기열 진입과 상태 조회의 수에 상한을 둔다. 넘으면 기다리지 않고 바로 503을 준다. 클라이언트가 이미 포기한 요청에 스레드와 DB 커넥션을 쓰지 않게 하려는 것이다.

- 응답: 503 `DROP_QUEUE_BUSY`와 `Retry-After`(초) 헤더. 오류 본문은 공통 형식이다.
- 대상은 `POST .../queue/entries`와 `GET`, `HEAD .../queue/me`뿐이다. 재고 점유(TX1), 결제 준비와 승인, 결제 확정은 거절하지 않는다. 사용자별 요청 횟수 제한(429)은 그대로이고 먼저 적용한다.
- 진입과 조회는 서로 다른 상한을 쓴다. 기본값은 `restless.drop.queue.concurrency.entry-max` 8, `status-max` 30이다. Hikari 풀 20과 Tomcat 스레드 200을 근거로 했고, 풀을 줄이면 같이 줄인다. 상한 값은 재측정으로 다시 정한다.
- `Retry-After`는 진입 5초(`entry-retry-after-seconds`), 조회 1초(`status-retry-after-seconds`)가 기본이다. 진입은 2026-11-25에 3초에서 5초로 늘렸다 (#423). 같은 조건 부하 측정에서 진입 오류가 1,694건에서 13건으로 줄었다. 진입은 사용자별 제한이 10초에 5회이고, 5초 x 5회는 25초라 계약대로 재시도하는 클라이언트가 429를 받지 않는다. 재시도 간격 x 제한 횟수가 제한 창보다 짧으면 기동이 실패한다.
- 503은 서버 상태를 바꾸지 않는다. 진입은 행을 만들지 않고 조회는 heartbeat를 갱신하지 않는다. 같은 요청을 다시 보내도 안전하다.
- 클라이언트는 `Retry-After` 뒤에 다시 보낸다. 한꺼번에 몰리지 않게 지터를 더한다. 이 응답은 대기를 잃는 오류가 아니다. 이탈 판정 시간 안에 조회가 한 번도 닿지 않으면 대기가 닫힐 수 있다.
- 메트릭 `drop.queue.busy`(태그 `kind`는 entry, status)가 거절 수를 센다.

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
