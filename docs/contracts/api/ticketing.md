# 티켓팅 API

- 담당: 송혜민
- 출처: [design.md 8.3](../../domains/ticketing/design.md)
- 상태: Draft

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/concerts/{concertId}/sessions` | 공연 회차 목록 |
| GET | `/api/concert-sessions/{sessionId}/seats` | 회차별 좌석 상태 (3초 폴링) |
| POST | `/api/ticket-reservations` | 좌석 선점 (`Idempotency-Key`) |
| GET | `/api/ticket-reservations/{reservationId}` | 예약 상세 |
| GET | `/api/ticket-reservations/active` | 활성 예약 조회, 복구 |
| DELETE | `/api/ticket-reservations/{reservationId}` | 선점 취소 |
| POST | `/api/ticket-reservations/{reservationId}/checkout` | 결제 시작 |
| POST | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 요청 |
| GET | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 상태 조회 |

## 좌석 선점

요청:

```http
POST /api/ticket-reservations
Idempotency-Key: 7f83a2b1-...
Content-Type: application/json

{ "sessionId": 100, "seatIds": [10, 11] }
```

성공:

```json
{
  "reservationId": 5001,
  "status": "HELD",
  "seatIds": [10, 11],
  "holdExpiresAt": "2026-11-06T11:30:00+09:00"
}
```

## 오류 코드

| 코드 | 의미 |
|---|---|
| `SEAT_UNAVAILABLE` | 이미 선점된 좌석 포함 |
| `ACTIVE_RESERVATION_EXISTS` | 진행 중 예약 있음 |
| `TICKET_LIMIT_EXCEEDED` | 구매 제한 초과 |
| `RESERVATION_EXPIRED` | 선점 만료 |
| `BOOKING_NOT_OPEN` | 예매 시작 전 |
| `BOOKING_CLOSED` | 예매 마감 |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 요청 |
| `SEAT_LOCK_TIMEOUT` | 잠금 대기 초과 |

HTTP 상태 코드는 원본에 없다. 공통 규칙에 맞춰 담당자가 정한다.
