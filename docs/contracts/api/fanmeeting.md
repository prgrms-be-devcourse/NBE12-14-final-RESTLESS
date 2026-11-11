# 팬미팅 API

- 담당: 홍승연
- 출처: 담당자 팀 명세 8.3
- 상태: Draft

| Method | Endpoint | 기능 | 권한 |
|---|---|---|---|
| POST | `/api/admin/fan-meetings` | 행사 등록 | ADMIN |
| GET | `/api/fan-meetings/{eventId}` | 행사 상세 | 공개 [확인] |
| POST | `/api/fan-meetings/{eventId}/applications` | 응모·재응모 | 회원 |
| DELETE | `/api/fan-meetings/{eventId}/applications/me` | 응모 취소 | 회원 |
| GET | `/api/fan-meetings/{eventId}/applications/me` | 내 응모 | 회원 |
| GET | `/api/fan-meetings/{eventId}/result/me` | 내 결과·결제 마감 시각 | 회원 |
| GET | `/api/fan-meetings/{eventId}/orders/me` | 내 주문·결제 상태 | 회원 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/payment-attempts` | 결제 시작 | 회원 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/decline` | 결제 포기 | 회원 |
| POST | `/api/fan-meetings/{eventId}/leftover-purchases` | 잔여석 구매 (P1) | 회원 |
| GET | `/api/admin/fan-meetings/{eventId}/summary` | 운영 현황 | ADMIN |
| POST | `/api/admin/fan-meetings/{eventId}/orders/{orderId}/review-resolution` | 보류 수동 처리 (P1) | ADMIN |

- 권한 열은 원본에 없어 경로 기준으로 채웠다 (`/admin`은 ADMIN, `/me`와 결제·응모는 회원).
- 결제 시작은 `Idempotency-Key` 헤더로 중복 요청을 막는다. 금액과 회원은 서버 값만 쓴다.
- 본인 주문만 조회·결제·포기할 수 있다. 다른 회원의 주문은 404로 응답한다.

## 결제 시작

```http
POST /api/fan-meetings/100/orders/5001/payment-attempts
Idempotency-Key: 7f83a2b1-...
```

실패 응답:

```json
{
  "code": "PAYMENT_WINDOW_EXPIRED",
  "message": "결제 가능 시간이 지났어요."
}
```

## 오류 코드

| 코드 | 의미 |
|---|---|
| `APPLICATION_WINDOW_CLOSED` | 응모 시간이 아님 |
| `ALREADY_APPLIED` | 이미 응모함 |
| `APPLICATION_NOT_ELIGIBLE` | 응모 자격 없음 |
| `ORDER_NOT_FOUND` | 주문 없음 또는 본인 주문 아님 |
| `PAYMENT_WINDOW_EXPIRED` | 결제 기한 지남 |
| `PAYMENT_UNDER_REVIEW` | 결제 결과 확인 중 (보류) |
| `DECLINE_NOT_ALLOWED` | 결제를 시도한 주문은 포기할 수 없음 |
| `ORDER_NOT_PAYABLE` | 결제할 수 없는 주문 상태 |
| `LEFTOVER_SOLD_OUT` | 잔여석 매진 |

HTTP 상태 코드는 원본에 `DECLINE_NOT_ALLOWED`(409), `LEFTOVER_SOLD_OUT`(409)만 나와 있다. 나머지는 담당자가 정한다. 오류 응답 형식은 [공통 규칙](README.md)을 따른다.
