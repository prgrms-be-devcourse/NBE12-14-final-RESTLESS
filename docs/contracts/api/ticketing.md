> 구현 범위 변경 (DECIDED, 2026-11-12): 전용 멱등성 기록·실행권·접수 작업 자동 복구는 후속 구현이다. 아래 범용 202/statusUrl과 거절 응답 재현은 목표 계약이며 현재 MVP 보장으로 표시하지 않는다. 기존 자원의 중복 생성·변경 방지와 불명확 결제 보호·재확인은 필수다. [구현 범위](../../domains/ticketing/idempotency.md)를 따른다.
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

아래 멱등성 계약의 202·409 외 오류의 HTTP 상태 코드는 후속 API 설계에서 정한다.

## 결제 기한 정책 (DECIDED)

최초 checkout에서 부여한 결제 기한 안에 서버가 유효한 승인 시도를 기록하고 그 준비 트랜잭션이 커밋돼야 한다.
기록 시각은 필요한 잠금 획득 후 DB 기준 UTC로 판단하며, payment_expires_at과 같거나 이후이면 신규 시도를 거절한다.
기한 내 유효 시도의 성공 확인이 늦어져도 예매를 확정한다. 진행 중·불명확 시도는 기한 경과만으로 해제하지 않는다.
브라우저 시각이나 HTTP 접수 시각만으로 자격을 부여하지 않는다. 응답 필드와 상세 오류 코드는 후속 계약 대상이다.
[트랜잭션 설계](../../domains/ticketing/transactions.md)를 따른다.

## 멱등성 키 범위 (DECIDED)

키 범위는 인증된 사용자 + 서버가 정의한 작업 종류 + Idempotency-Key다.
회차·예약·주문 ID는 URL 경로를 포함한 요청 내용으로 비교한다. 같은 범위·키에 다른 요청은 IDEMPOTENCY_KEY_CONFLICT다.
MVP에서는 키를 삭제·재사용하지 않는다. 처리 중 동일 요청에는 202 Accepted, REQUEST_PROCESSING과 본인 작업 조회용 statusUrl을 반환한다.
같은 키의 다른 요청은 409 IDEMPOTENCY_KEY_CONFLICT다.
확정된 비즈니스 거절은 같은 결과를 반환하고, 전체 롤백·외부 부작용 없음이 확인된 내부 오류만 같은 키 재시도를 허용한다.
결과 불명확은 기존 작업 확인·복구로 연결하며 새 승인·환불을 만들지 않는다. PG 최종 실패 후 기한 내 새 승인 시도는 새 키를 사용한다.
상세 조회 경로·DTO·영속화 구조는 후속 설계 대상이다. [멱등성 계약](../../domains/ticketing/idempotency.md)을 따른다.

## MVP 공연장·배치 권한 정책 (DECIDED)

운영자가 공연장·배치를 사전 등록하며 주최자는 회차에 해당 공연장의 등록 배치만 선택한다.
예매 오픈 정각부터 회차 배치 변경을 금지한다. 배치 원본 수정으로 이를 우회해서도 안 된다.
MVP에서는 배치를 초기 데이터로 등록하고, 미등록 배치는 별도 요청 화면 없이 주최자 자료 전달·운영자 검토 후 등록한다. 배치 편집·등록 관리자 화면과 자동 좌석 생성은 제공하지 않는다. 초기 적재/추가 등록 절차·선택 API·공통 권한 매핑은 후속 구현 계약 대상이다. [배치 정책](../../domains/ticketing/venue-consistency.md)을 따른다.

## 구매 제한·활성 예약 (DECIDED)

사용자·회차별 HELD/PAYMENT_PENDING/RECONCILING 예약은 최대 1건이다. 다른 키로 요청해도 기존 활성 예약이 있으면 ACTIVE_RESERVATION_EXISTS로 거절한다.
동일 키 재전송은 기존 예약으로 연결한다. 확정 수량+선점 수량+신규 요청 수량이 회차별 한도를 넘으면 TICKET_LIMIT_EXCEEDED다.
환불 확정 전에는 확정 수량을 복원하지 않는다. 상세는 [구매 제한 설계](../../domains/ticketing/purchase-limit.md)를 따른다.

## MVP 적용 범위: 선점과 checkout (DECIDED)

선점은 사용자별 request_key UNIQUE와 최초 요청 해시 비교로 성공한 요청을 재현한다. 다른 회차·좌석의 키 재사용은 충돌이다.
checkout은 예약 잠금·기존 주문 참조로 중복 생성과 기한 연장을 막는다. 범용 사용자·작업별 키 충돌 계약은 MVP checkout에 적용하지 않는다.
결제 키는 공통 계약과 후속 조율한다. [세부 규칙](../../domains/ticketing/idempotency.md)을 따른다.

## 내부 회차 명칭

Java 엔티티·Repository는 Performance/PerformanceRepository, DB 테이블은 performances로 통일한다.
API 호환성을 위해 /api/concert-sessions 경로와 기존 sessionId 요청 필드는 그대로 유지한다.
API의 sessionId는 내부 Performance의 ID로 해석한다.
