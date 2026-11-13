# 공통 API (초안)

- 담당: 홍승연 (인증, 회원, 공통 오류)
- 상태: PROPOSED. 드롭과 다른 도메인이 기대하는 기능을 모은 초안이다. 인증은 JWT로 정했다(아래 "인증 규칙").

## 인증, 회원

| Method | Endpoint | 기능 | 상태 |
|---|---|---|---|
| POST | `/api/auth/signup` | 회원가입 `{email, password, nickname}` → 201 `{memberId}` | 구현 |
| POST | `/api/auth/login` | 로그인 → 200 `{accessToken, tokenType, expiresIn}` + Refresh 쿠키 | 구현 |
| POST | `/api/auth/refresh` | Refresh 쿠키로 새 Access, 새 Refresh 쿠키 발급 | 구현 |
| POST | `/api/auth/logout` | 이 기기의 Refresh 폐기, 쿠키 삭제 → 204 | 구현 |
| GET | `/api/me/profile` | 내 프로필, 기본 배송지 → 200 프로필 | 구현 |
| PATCH | `/api/me/profile` | 닉네임, 기본 배송지 수정 → 200 바뀐 프로필 | 구현 |
| POST | `/api/me/withdraw` | 탈퇴 `{password}` → 204 | 구현 |

### 프로필

응답 (조회, 수정 공통). 기본 배송지를 등록하지 않았으면 null이다.

```json
{
  "memberId": 1,
  "email": "user@example.com",
  "nickname": "철수",
  "role": "USER",
  "defaultRecipient": "김철수",
  "defaultPhone": "010-1234-5678",
  "defaultAddress": "서울시 강남구 1"
}
```

수정 요청은 보낸 필드만 바꾼다.

| 필드 | 규칙 |
|---|---|
| `nickname` | 2~30자, 공백만은 안 됨. 지울 수 없음 |
| `defaultRecipient` | 50자까지 |
| `defaultPhone` | 20자까지, 숫자와 `-`만 |
| `defaultAddress` | 255자까지 |

- 필드를 빼거나 null이면 그대로 둔다. 기본 배송지 필드에 `""`를 보내면 그 값을 지운다.
- 이메일, 역할은 바꿀 수 없다(보내도 무시).
- 탈퇴한 회원은 Access Token이 남아 있어도 404 `MEMBER_NOT_FOUND`.
- 드롭 주문의 "기본 배송지 불러오기"는 이 조회 API를 쓴다.

### 탈퇴

- 비밀번호를 다시 받는다. 틀리면 400 `MEMBER_PASSWORD_MISMATCH`.
- 행은 지우지 않는다(`status = WITHDRAWN`, `deleted_at`). 주문, 결제, 응모 같은 다른 도메인 기록은 그대로 남는다.
- 모든 기기의 Refresh Token을 폐기한다. 다시 로그인할 수 없고, 같은 이메일로 다시 가입할 수도 없다(`uk_users_email`).
- Access Token은 만료(최대 1시간)까지 남는다. 회원 API는 404를 주지만 다른 도메인 API는 통과할 수 있으니, 결제·응모처럼 중요한 처리는 서비스에서 `memberService.getActive(userId)`로 한 번 더 확인한다.
- **진행 중인 거래가 있으면 막는다 (`WithdrawalGuard`).** 도메인은 `member.application.WithdrawalGuard`를 구현한 `@Component`를 자기 패키지에 두면 된다. 하나라도 `true`를 돌려주면 409 `MEMBER_HAS_ACTIVE_PARTICIPATION`. 지금 등록된 Guard는 없다.

```java
@Component
class FanMeetingWithdrawalGuard implements WithdrawalGuard {
    @Override
    public boolean hasActiveParticipation(Long userId) {
        // 이 도메인에서 탈퇴를 막아야 할 진행 중인 거래가 있으면 true
    }
}
```

- 탈퇴가 끝나면 `MemberWithdrawnEvent(userId)`가 같은 트랜잭션에서 발행된다. 인증은 이 이벤트로 Refresh Token을 폐기한다.

## 인증 규칙

- **Access Token**: JWT(HS256), 1시간. 로그인 응답 본문으로 주고, 요청 때 `Authorization: Bearer <accessToken>` 헤더로 보낸다.
- **Refresh Token**: 7일. `refresh_token` HttpOnly 쿠키(`Secure`, `SameSite=Strict`, `Path=/api/auth`)로만 오간다. 프론트는 fetch에 `credentials: 'include'`를 준다. 재발급하면 새 토큰으로 바뀌고, 이미 쓴 토큰이 다시 오면 그 로그인의 토큰을 모두 폐기한다.
- **로그인 필요**: 아래 공개 경로를 뺀 모든 API. 토큰이 없거나 만료·위조면 401 `UNAUTHORIZED`.
- **공개 경로**: 가입·로그인·재발급·로그아웃, 공연·회차 좌석·드롭·경매·팬미팅 상세 조회(GET), `/actuator/health`. 로그인 없이 볼 조회 API를 만들면 `SecurityConfig.PUBLIC_GET_PATHS`에 추가한다.
- **관리자**: `/api/admin/**`는 `role = ADMIN`만. 아니면 403 `FORBIDDEN`. 관리자 기능은 이 경로 아래로 만든다(컨트롤러에서 권한을 다시 검사하지 않는다). 가입하면 USER이고, ADMIN은 DB에서 지정한다.
- **로그인 회원 ID**: 컨트롤러 파라미터에 `@LoginMember Long userId`를 쓴다. 요청 본문으로 회원 ID를 받지 않는다.
- **현재 시각**: `Instant.now()` 대신 `Clock`을 주입받아 `clock.instant()`로 읽는다(`common/config/ClockConfig`). 테스트에서 시각을 바꿀 수 있다.

## 오류 응답 규칙

- **형식**: 모든 오류는 `{code, message, details, traceId, timestamp}`([README 공통 규칙 3](README.md)). 필터 단계의 401, 403도 같다. 성공 응답은 감싸지 않는다.
- **도메인 오류**: `ErrorCode`(한 파일)에 `도메인_상황` 이름으로 추가하고 `throw new BusinessException(ErrorCode.X)`. 추가 정보는 `new BusinessException(ErrorCode.X, Map.of("seatIds", ids))`처럼 `details`로 준다. 없으면 `details`는 null.
- **traceId**: 요청마다 서버가 만들어 응답 헤더 `X-Trace-Id`와 로그 줄(`[traceId]`)에 넣는다. 오류를 전달받으면 이 값으로 서버 로그를 찾는다.
- **공통 처리**:

| 상황 | 상태 | code | details |
|---|---|---|---|
| 입력 검증 실패 | 400 | `INVALID_REQUEST` | `fields`: 필드별 메시지 |
| 필수 헤더 누락 (`Idempotency-Key` 등) | 400 | `INVALID_REQUEST` | `header`: 헤더 이름 |
| 필수 쿼리 값 누락 | 400 | `INVALID_REQUEST` | `parameter`: 값 이름 |
| 본문 해석 불가, 타입 불일치 | 400 | `INVALID_REQUEST` | |
| 없는 경로 | 404 | `NOT_FOUND` | |
| 지원하지 않는 메서드 | 405 | `METHOD_NOT_ALLOWED` | |
| 업로드 크기 초과 | 413 | `FILE_SIZE_EXCEEDED` | |
| 지원하지 않는 Content-Type | 415 | `UNSUPPORTED_MEDIA_TYPE` | |
| 그 밖의 예외 | 500 | `INTERNAL_ERROR` | (로그에 스택과 traceId) |

## 공연

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/concerts` | 공연 목록 |
| GET | `/api/concerts/{concertId}` | 공연 상세 (연결된 드롭, 경매 포함 여부는 미정) |

공연 회차 API는 티켓팅 문서에 있다.

## 주문, 결제

| Method | Endpoint | 기능 / 응답 |
|---|---|---|
| GET | `/api/me/orders` | 내 주문 목록 (후속 구현) |
| GET | `/api/orders/{orderId}` | 주문 상세 (후속 구현) |
| POST | `/api/payments` | 결제 준비. 새 시도 201, 같은 키 재요청 200 |
| POST | `/api/payments/confirm` | 토스 승인. 승인 200, 처리 중/불명확 202, 최종 거절 409 |
| GET | `/api/payments/{paymentId}` | 본인 결제 조회 200 |
| POST | `/api/payments/{paymentId}/refunds` | 손님 전액 환불. 완료 200, 처리 중/불명확 202, 거절/상태 충돌 409 |

기존 초안의 `/api/payments/orders/{orderId}`는 `/api/payments/{paymentId}`로 변경했다.
환불 초안 경로 `/api/orders/{orderId}/refunds`는 `/api/payments/{paymentId}/refunds`로 변경했다. 팬미팅처럼 공통 주문 없이도 결제 대상을 식별하기 위해 결제 ID를 기준으로 한다.
결제 대상은 `(payableType, payableId)`로 식별하며 `orderId`는 승인 요청에서만 우리 `pgOrderId`를 뜻한다.
모든 결제 API는 Bearer 로그인이 필요하다(토큰 없음/만료/위조는 401 `UNAUTHORIZED`).
회원 ID는 토큰의 `@LoginMember`에서만 받고 본문에서 받지 않는다.

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
미완료 승인/취소는 [공통 결제 대사](../../operations/failure-handling.md#공통-결제-대사-46)가 PG 조회로 복구한다. 손님 환불도 같은 취소 확정 경로를 쓴다.

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
| 202 | `{ "refundId": 10, "status": "UNKNOWN" }` | PG 취소 결과 불명확, 결제 CANCEL_UNKNOWN. 대사가 조회/같은 키 취소 재시도로 확정 |
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
결제 행 → 환불 행 → 도메인 행 순서로 잠그며, PG 전액 취소가 확인되기 전에는 재고·좌석을 반환하지 않는다.
결과 반영 Handler의 기술 예외는 결제·환불·도메인 변경을 함께 롤백하고 202로 반환한다. 대사가 같은 저장 키로 복구한다.
PG의 명시적 거절로 FAILED가 된 경우에는 새 키로 다시 요청할 수 있다. 완료된 환불은 다른 키로 중복 요청할 수 없다.

관측: `payment.refund{result=requested|completed|rejected|unknown}`는 **커밋된 손님 환불 상태 전이**를 센다.
신규 환불 건수는 `result=requested`, 완료 건수는 `result=completed`로 조회한다. 같은 키 재요청·중복 결과는 늘리지 않는다.
`unknown`은 REQUESTED → UNKNOWN 최초 전이만 세며, 대사 완료/거절도 동일하게 집계한다. 롤백은 집계하지 않는다.
API 응답 횟수(멱등 재요청 포함)는 `payment.refund.request{result=completed|rejected|unknown|not_refundable}`로 분리했다(REQUESTED 응답은 unknown 분류).
프로세스 재시작·메트릭 전송 장애로 카운터가 유실될 수 있으므로 회계상 정본은 DB이며 상태별 카운터를 합산해 환불 총수로 쓰지 않는다.
`payment.pg.cancel{kind=CUSTOMER_REFUND|COMPENSATION}` Timer는 손님 환불·보상 취소·대사의 실제 PG 취소 호출을 모두 측정한다.

### 결제 오류

공통 오류 응답 `{code, message, details, traceId, timestamp}`를 사용한다.

| 상태 | code | 조건 / details |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 Idempotency-Key 누락, 길이·본문·타입 검증 실패 |
| 404 | `PAYMENT_NOT_FOUND` | 없는 결제 또는 타인 결제 조회·승인·환불 |
| 404 | `PAYMENT_TARGET_NOT_FOUND` | 타인 소유 대상 또는 타인 소유 기존 시도 준비 |
| 409 | `PAYMENT_NOT_PAYABLE` | Handler load empty/미등록, 이미 승인·취소 진행, 승인 시작 거절 (`details.reason`) |
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
