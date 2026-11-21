# 주문·결제 API

> 담당: 이태호
>
> 상태: IMPLEMENTED (코드 기준). 코드와 다르면 코드를 기준으로 이 문서를 고친다.
>
> 관련 코드: `backend/src/main/java/com/restless/order/`, `backend/src/main/java/com/restless/payment/`
>
> 마지막 확인: 2026-11-21 (#314 최근 결제 조회 추가. `common.md`에서 분리. 이전 이력: #44, #45~#47, #73, #101, #109)

오류 응답 형식은 [README 공통 규칙 3](README.md#공통-규칙)을 따른다. 결제를 쓰는 도메인: [드롭](drop.md), [티켓팅](ticketing.md), [팬미팅](fanmeeting.md).

## 엔드포인트

| Method | Endpoint | 기능 / 응답 | 구현 |
|---|---|---|---|
| GET | `/api/me/orders` | 내 주문 목록. 200 페이지 (#101) | 구현 |
| GET | `/api/orders/{orderId}` | 주문 상세. 결제 대상과 최근 결제 포함 200 (#109) | 구현 |
| POST | `/api/payments` | 결제 준비. 새 시도 201, 같은 키 재요청 200 | 구현 |
| POST | `/api/payments/confirm` | 토스 승인. 승인 200, 처리 중/불명확 202, 최종 거절 409 | 구현 |
| GET | `/api/payments/{paymentId}` | 본인 결제 조회 200 | 구현 |
| GET | `/api/payments/latest?payableType=FAN_MEETING&payableId=10` | 본인 대상의 최근 결제 시도 200, 이력 없음 204 | 구현 |
| POST | `/api/payments/{paymentId}/refunds` | 손님 전액 환불. 완료 200, 처리 중/불명확 202, 거절/상태 충돌 409 | 구현 |

기존 초안의 `/api/payments/orders/{orderId}`는 `/api/payments/{paymentId}`로 변경했다.
환불 초안 경로 `/api/orders/{orderId}/refunds`는 `/api/payments/{paymentId}/refunds`로 변경했다. 팬미팅처럼 공통 주문 없이도 결제 대상을 식별하기 위해 결제 ID를 기준으로 한다.
결제 대상은 `(payableType, payableId)`로 식별하며 `orderId`는 승인 요청에서만 우리 `pgOrderId`를 뜻한다.
모든 결제 API는 Bearer 로그인이 필요하다(토큰 없음/만료/위조는 401 `UNAUTHORIZED`).
회원 ID는 토큰의 `@LoginMember`에서만 받고 본문에서 받지 않는다.

### 최근 결제 시도 조회

`GET /api/payments/latest?payableType=FAN_MEETING&payableId=10`은 본인의 해당 대상에 대한 가장 최근 시도를 `{ "paymentId": 12, "status": "UNKNOWN" }`로 반환한다. `payableType`은 `DROP`, `TICKET`, `FAN_MEETING`이고 `payableId`는 양수이며 둘 다 필수다. 잘못된 값은 400이다.

- 이력이 없거나 타인 소유 대상이면 동일하게 본문 없는 204다. 대상의 존재 여부를 노출하지 않는다.
- 조회는 결제를 생성하거나 PG를 호출하지 않는다. 반환된 ID로 `GET /api/payments/{paymentId}`를 조회해 결과 확인을 이어 간다.
- 팬미팅 자체 주문처럼 공통 주문 상세를 쓰지 않는 화면도 PG 복귀와 새로고침 뒤 결제 상태를 복원할 수 있다. 실패 후 재시도가 있으면 과거 FAILED 대신 가장 최근 시도를 준다.

### 내 주문 목록

`GET /api/me/orders?page=0&size=20` — 로그인한 회원의 공통 주문을 최신순으로 준다 (#101).

```json
{
  "items": [
    {
      "orderId": 7,
      "orderType": "DROP",
      "status": "PAID",
      "totalAmount": 39000,
      "createdAt": "2026-11-14T01:00:00Z",
      "lines": [
        { "itemType": "DROP_VARIANT", "itemName": "투어 후드", "optionName": "M", "unitPrice": 39000, "quantity": 1 }
      ]
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

- 본인 주문만 준다. 공통 주문인 `DROP`, `TICKET`만 나온다. 팬미팅은 자체 주문을 쓰므로 나오지 않는다.
- 모든 상태를 준다. 결제 전, 취소, 환불 주문도 나온다. `status`는 주문의 지금 상태다.
- `lines`의 이름, 옵션, 가격은 주문할 때 복사해 둔 값이다. 상품 정보가 나중에 바뀌어도 그대로다. 옵션이 없으면 `optionName`은 빈 문자열이다. `lines`는 주문에 담은 순서다.
- 정렬은 주문 시각 내림차순이다. 시각이 같으면 `orderId` 내림차순이다.
- `page`는 0부터, 기본 0이다. `size`는 1~100, 기본 20이다. 음수 `page`, 범위 밖 `size`, 숫자가 아닌 값은 400 `INVALID_REQUEST`다.
- 주문이 없거나 범위 밖 페이지면 `items`는 빈 배열이다. `totalElements`, `totalPages`는 실제 전체 주문 기준이다.
- 토큰이 없으면 401 `UNAUTHORIZED`다. 탈퇴한 회원은 토큰이 남아 있어도 404 `MEMBER_NOT_FOUND`다. 탈퇴 안내대로 탈퇴 뒤에는 주문을 볼 수 없다.
- 배송지와 결제 ID는 주지 않는다. 결제 연결은 주문 상세에서 다룬다.

### 주문 상세

`GET /api/orders/{orderId}` — 본인 주문 하나를 준다. 환불과 재결제에 필요한 결제 대상과 최근 결제를 함께 준다 (#109).

```json
{
  "orderId": 7,
  "orderType": "DROP",
  "status": "PAID",
  "totalAmount": 39000,
  "createdAt": "2026-11-14T01:00:00Z",
  "lines": [
    { "itemType": "DROP_VARIANT", "itemName": "투어 후드", "optionName": "M", "unitPrice": 39000, "quantity": 1 }
  ],
  "shipping": { "recipient": "홍길동", "phone": "010-1234-5678", "address": "서울시 강남구 1" },
  "payable": { "payableType": "DROP", "payableId": 5 },
  "payableExpiresAt": "2026-11-14T01:05:00Z",
  "payment": { "paymentId": 12, "status": "APPROVED" }
}
```

- 목록 한 줄과 같은 필드에 `shipping`, `payable`, `payableExpiresAt`, `payment`를 더한다.
- `shipping`: 주문할 때 복사한 배송지다. 배송이 없는 주문(티켓)은 null이다.
- `payable`: 결제 준비 `POST /api/payments`에 그대로 넘기는 결제 대상이다. 드롭 주문은 `DROP`과 재고 점유 ID, 티켓 주문은 `TICKET`과 연결된 티켓팅 예약 ID다. 연결 예약이나 도메인 Resolver가 없으면 null이다.
- `payableExpiresAt`: 결제 대상의 결제 기한이다. 드롭은 재고 점유 만료 시각, 티켓은 최초 checkout의 payment_expires_at이다. 모르면 null이다.
- `payment`: 그 결제 대상의 가장 최근 결제 시도다. 실패 뒤 다시 결제했으면 새 시도가 나온다. `status`는 결제 조회와 같은 값이다. 결제를 시작하지 않았으면 null이다. 환불은 이 `paymentId`로 요청한다.
- 결제 가능 여부와 환불 가능 여부는 주지 않는다. 최종 판단은 결제 준비와 환불 API의 응답이다. 티켓팅은 기한 경과·예약 확정·반환 이후에도 연결 대상과 원래 기한을 제공하며, 이 필드가 존재한다는 사실이 재결제를 허용하지 않는다.
- 남의 주문이나 없는 주문은 404 `ORDER_NOT_FOUND`다. 숫자가 아닌 ID는 400 `INVALID_REQUEST`다.
- 토큰이 없으면 401 `UNAUTHORIZED`다. 탈퇴한 회원은 404 `MEMBER_NOT_FOUND`다.
- 도메인 연결: 주문 모듈의 결제 대상 조회 인터페이스를 각 도메인이 구현한다. 주문 ID를 받아 결제 대상과 기한을 돌려준다. 구현이 없는 주문 종류는 `payable`이 null이다. 최근 결제는 주문 모듈이 결제 모듈의 읽기 메서드로 찾는다.

### 결제 준비

`POST /api/payments`, 필수 헤더 `Idempotency-Key`: 공백만인 값을 제외한 1~64자.

```json
{ "payableType": "DROP", "payableId": 123 }
```

`payableType`은 `DROP`, `TICKET`, `FAN_MEETING`, `payableId`는 양수다.
각 도메인의 Handler가 등록되어 있어야 한다(Handler 구현은 도메인 담당 범위).
금액, 상품명, 기한은 Handler가 읽은 서버 값이며 클라이언트 금액을 받지 않는다.
0원 대상은 PG로 결제하지 않는다. 409 `PAYMENT_NOT_PAYABLE`, `details.reason = "FREE_ORDER"`. 무료 주문은 도메인이 결제 없이 확정한다.
Handler를 부르는 결제 트랜잭션은 `READ_COMMITTED`다. Handler는 판정에 쓰는 도메인 행을 잠근 뒤 읽는다.

201 (신규) 또는 200 (같은 대상·키의 기존 시도):

```json
{
  "paymentId": 1,
  "pgOrderId": "PAY-4edccaf24ce44ae2b1d3d22125753385",
  "amount": 39000,
  "orderName": "후드티 M",
  "expiresAt": "2026-11-13T01:05:00Z",
  "status": "READY",
  "created": true
}
```

기한이 없으면 `expiresAt`은 null이다. 같은 키는 `created: false`와 현재 상태를 반환한다.
대상 조회·소유자·기한 검증은 기존 시도 반환보다 먼저 수행한다.
다른 키가 오면 READY 시도는 `FAILED`(`failureCode=SUPERSEDED`)로 닫고 새 READY를 만든다.
승인 전 시도 교체에는 Handler 결과 콜백을 호출하지 않는다.
PROCESSING, UNKNOWN, 승인·취소 진행 상태가 있으면 새 시도를 만들지 않는다.
대상당 진행 중 시도는 DB UNIQUE로 1건이며 충돌 후 한 번 새 트랜잭션에서 재조회한다.

### 결제 승인

`POST /api/payments/confirm` (토스 successUrl 파라미터 이름):

```json
{ "paymentKey": "payment-key", "orderId": "PAY-4edccaf24ce44ae2b1d3d22125753385", "amount": 39000 }
```

`paymentKey`는 공백이 아닌 200자 이하, `orderId`는 공백이 아닌 64자 이하, `amount`는 필수 0 이상의 정수다.
`amount`가 저장 금액과 다르면 READY를 유지하고 PG를 호출하지 않는다.
승인 시작을 커밋한 뒤 트랜잭션 밖에서 PG를 한 번 호출하며, 결과는 새 트랜잭션에서 Handler와 함께 반영한다.
동일 시도의 승인 키는 `approve-{paymentId}`로 고정한다. 불명확한 결과를 재승인하지 않는다.

200:

```json
{ "paymentId": 1, "status": "APPROVED" }
```

202 (처리 중, 결과 불명확 또는 결과 반영 기술 오류로 PROCESSING 유지):

```json
{ "paymentId": 1, "status": "PROCESSING" }
```

PG 결과가 불명확하거나 PG 승인 금액이 저장 금액과 다르면 위 본문의 `status`는 `UNKNOWN`이다.
READY 외 상태의 중복 요청은 PG를 호출하지 않는다. PROCESSING/UNKNOWN은 202, APPROVED는 200,
FAILED는 409 `PAYMENT_FAILED`, 취소 계열은 409 `PAYMENT_NOT_PAYABLE`이다.

승인 후 도메인이 거절하면 보상 취소를 요청한다. 결제·환불 요청·도메인 거절 상태를 먼저 함께 커밋하고,
트랜잭션 밖에서 PG 취소 후 결과를 새 트랜잭션에서 반영한다.
응답은 409 `PAYMENT_REJECTED`, `details`는 `{ "reason": "도메인 거절 사유", "refund": "COMPLETED" }`이다.
`refund`는 `COMPLETED`, `UNKNOWN`, `REJECTED` 중 하나다.
취소 성공은 CANCELLED / 환불 COMPLETED, 불명확은 CANCEL_UNKNOWN / 환불 UNKNOWN,
취소 확정 거절은 APPROVED / 환불 FAILED로 남아 운영 확인 대상이다.
취소 결과 반영 기술 오류는 CANCEL_PENDING / 환불 REQUESTED를 유지하고 `refund: UNKNOWN`으로 응답한다.
미완료 승인/취소는 [미완료 결제 재확인 작업](../../operations/failure-handling.md#미완료-결제-재확인-작업-46)이 PG 조회로 복구한다. 손님 환불도 같은 취소 확정 경로를 쓴다.

### 결제 조회

`GET /api/payments/{paymentId}` — 본인 결제만 조회한다.

```json
{
  "paymentId": 1,
  "payableType": "DROP",
  "payableId": 123,
  "pgOrderId": "PAY-4edccaf24ce44ae2b1d3d22125753385",
  "status": "APPROVED",
  "amount": 39000,
  "approvalStartedAt": "2026-11-13T01:00:00Z",
  "approvedAt": "2026-11-13T01:00:01Z",
  "failureCode": null,
  "refund": null
}
```

시각과 `failureCode`는 아직 기록되지 않았으면 null이다. paymentKey는 반환하지 않는다.
`refund`는 가장 최근 요청한 환불 요약이며 없으면 null이다. 요청 시각 내림차순, 같은 시각이면 refundId 내림차순으로 고른다.
손님 환불과 보상 취소 모두 포함하며 본인 결제에서만 볼 수 있다. 예:

```json
{
  "refundId": 10,
  "kind": "CUSTOMER_REFUND",
  "status": "COMPLETED",
  "requestedAt": "2026-11-13T01:01:00Z",
  "completedAt": "2026-11-13T01:01:01Z"
}
```

`kind`: `CUSTOMER_REFUND` / `COMPENSATION`, `status`: `REQUESTED` / `UNKNOWN` / `COMPLETED` / `FAILED`.
완료 전 `completedAt`은 null이다.

### 손님 전액 환불

`POST /api/payments/{paymentId}/refunds`, 필수 헤더 `Idempotency-Key`: 공백만인 값을 제외한 1~64자.
요청한 회원의 결제만 환불한다. 금액을 클라이언트에서 받지 않으며 저장된 결제 금액 전액을 취소한다.

```json
{ "reason": "단순 변심" }
```

`reason`은 필수이며 공백만인 값을 제외한 1~200자다. 부분 환불은 지원하지 않는다.
0원 대상은 결제 준비에서 제외되므로 환불 대상이 아니며 환불 금액은 항상 양수다.

| HTTP | 응답/오류 | 의미 |
|---|---|---|
| 200 | `{ "refundId": 10, "status": "COMPLETED" }` | PG 전액 취소 확인, 결제 CANCELLED, `onRefunded(CUSTOMER_REFUND)` 커밋 |
| 202 | `{ "refundId": 10, "status": "UNKNOWN" }` | PG 취소 결과 불명확, 결제 CANCEL_UNKNOWN. 미완료 결제 재확인 작업이 조회/같은 키 취소 재시도로 확정 |
| 202 | `{ "refundId": 10, "status": "REQUESTED" }` | 최초 취소 진행 중인 같은 키의 재요청, 또는 결과 반영 기술 오류로 CANCEL_PENDING/REQUESTED 유지 |
| 409 | `PAYMENT_REFUND_REJECTED`, `details.code` | PG 취소 최종 거절, 환불 FAILED·결제 APPROVED, `onRefundRejected(CUSTOMER_REFUND)` 함께 커밋 |

결제 잠금 후 같은 키의 환불을 먼저 확인한다. 같은 결제·같은 키는 기존 refundId와 현재 결과를 반환하고 PG를 다시 호출하지 않는다.
FAILED 환불의 같은 키도 409와 저장된 PG 거절 코드를 재현한다. 같은 키의 reason을 달리 보내도 기존 요청을 반환하며 사유를 바꾸지 않는다.
클라이언트 키의 범위는 **결제별 손님 환불**이다. 다른 결제나 다른 회원이 같은 키를 사용해도 서로 영향을 주지 않고 기존 키의 존재를 노출하지 않는다.
클라이언트 키는 `client_key`에 저장한다. PG 취소에는 별도로 서버가 생성한 `refund-{UUID}` 키를 저장해 사용하며 보상 취소의 `compensation-{paymentId}` 키와 구분한다.
기존 환불의 PG 키는 변경하지 않고 새 `client_key`에 기존 값을 복사하므로 기존 요청 재현과 진행 중 취소 재시도도 유지된다.

새 키는 APPROVED 결제에서만 허용한다. 도메인 Handler의 `onRefundRequesting(CUSTOMER_REFUND)`이 환불 가능 조건을 판단하고
환불 대기 상태로 바꾼다. 기본 구현은 `REFUND_NOT_SUPPORTED`로 거절한다. 수락한 도메인은 필요 시 `onRefundRejected`도 구현해
PG 거절 때 환불 대기를 운영 확인 상태로 바꾼다(예: REFUND_PENDING → REFUND_REVIEW).
Handler 업무 거절은 요청 트랜잭션 전체를 롤백하므로 결제 APPROVED·환불 행 없음, PG 호출 없음이다.

요청 트랜잭션 커밋 → 트랜잭션 밖 PG 취소 한 번 → 기존 공통 취소 결과 반영 트랜잭션 순서다.
요청 트랜잭션은 결제 행을 잠근 뒤 Handler(도메인 행)를 호출하고 환불 행을 새로 만든다. 결과 반영 트랜잭션은 결제 행 → 환불 행 → 도메인 행 순서로 잠근다. PG 전액 취소가 확인되기 전에는 재고·좌석을 반환하지 않는다.
결과 반영 Handler의 기술 예외는 결제, 환불, 도메인 변경을 함께 롤백하고 202로 반환한다. 미완료 결제 재확인 작업이 같은 저장 키로 복구한다.
PG의 명시적 거절로 FAILED가 된 경우에는 새 키로 다시 요청할 수 있다. 완료된 환불은 다른 키로 중복 요청할 수 없다.

관측: `payment.refund{result=requested|completed|rejected|unknown}`는 **커밋된 손님 환불 상태 전이**를 센다.
신규 환불 건수는 `result=requested`, 완료 건수는 `result=completed`로 조회한다. 같은 키 재요청·중복 결과는 늘리지 않는다.
`unknown`은 REQUESTED → UNKNOWN 최초 전이만 세며, 미완료 결제 재확인 작업의 완료/거절도 동일하게 집계한다. 롤백은 집계하지 않는다.
API 응답 횟수(멱등 재요청 포함)는 `payment.refund.request{result=completed|rejected|unknown|not_refundable}`로 분리했다(REQUESTED 응답은 unknown 분류).
프로세스 재시작·메트릭 전송 장애로 카운터가 유실될 수 있으므로 회계상 정본은 DB이며 상태별 카운터를 합산해 환불 총수로 쓰지 않는다.
`payment.pg.cancel{kind=CUSTOMER_REFUND|COMPENSATION}` Timer는 손님 환불, 보상 취소, 미완료 결제 재확인 작업의 실제 PG 취소 호출을 모두 측정한다.

### 결제 오류

공통 오류 응답 `{code, message, details, traceId, timestamp}`를 사용한다.

| 상태 | code | 조건 / details |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 Idempotency-Key 누락, 길이·본문·타입 검증 실패 |
| 404 | `PAYMENT_NOT_FOUND` | 없는 결제 또는 타인 결제 조회·승인·환불 |
| 404 | `PAYMENT_TARGET_NOT_FOUND` | 타인 소유 대상 또는 타인 소유 기존 시도 준비 |
| 409 | `PAYMENT_NOT_PAYABLE` | Handler load empty/미등록, 이미 승인·취소 진행, 승인 시작 거절 (`details.reason`). 승인 시작 거절이면 그 시도는 FAILED(`APPROVAL_REJECTED`)로 커밋되고 Handler `onFailed`는 부르지 않는다. 다시 결제하려면 새 키로 준비한다 |
| 409 | `PAYMENT_WINDOW_EXPIRED` | 준비 또는 승인 시작 기한 초과. 승인 시도는 FAILED로 커밋 |
| 409 | `PAYMENT_ATTEMPT_IN_PROGRESS` | 다른 PROCESSING 시도 또는 재조회 후에도 준비 충돌 |
| 409 | `PAYMENT_UNDER_REVIEW` | 다른 UNKNOWN 시도가 있음 |
| 400 | `PAYMENT_AMOUNT_MISMATCH` | 승인 요청 금액과 저장 금액이 다름 |
| 409 | `PAYMENT_FAILED` | PG 확정 거절 또는 기존 FAILED. `details.code`에 PG/실패 코드 |
| 409 | `PAYMENT_REJECTED` | 승인 후 도메인 거절. `details.reason`, `details.refund` |
| 409 | `PAYMENT_NOT_REFUNDABLE` | APPROVED 외 결제 또는 Handler 환불 불가. Handler 거절은 `details.reason` |
| 409 | `PAYMENT_REFUND_IN_PROGRESS` | 다른 키의 CANCEL_PENDING/CANCEL_UNKNOWN 환불 또는 충돌 복구 중 |
| 409 | `PAYMENT_ALREADY_REFUNDED` | 새 키로 CANCELLED 결제 환불 요청 |
| 409 | `PAYMENT_REFUND_REJECTED` | PG 취소 최종 거절 또는 FAILED 키 재요청. `details.code` |

도메인 구매 확정은 같은 트랜잭션의 Handler가 담당한다([ADR-0003](../../decisions/0003-payment-module-boundary.md)).
티켓팅의 주문·예매 확정 경계는 [티켓팅 트랜잭션 설계](../../domains/ticketing/transactions.md)를 따른다.
