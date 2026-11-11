# Drop API 계약 (초안)

- 상태: PROPOSED. DTO, 에러 형식, 인증, URI는 팀 합의와 구현에 맞춰 확정한다.
- Base URL: `/api` ([ADR-0002](../../decisions/0002-common-conventions.md))

## 사용자 API

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/drops` | 목록, 검색, 정렬 |
| GET | `/drops/{dropId}` | 상세, SKU, 품절 상태 |
| GET | `/drops/{dropId}/checkout-quote` | 결제 예정 금액 |
| POST | `/drops/{dropId}/queue/entries` | 결제하기 클릭 후 대기열 진입 |
| GET | `/drops/{dropId}/queue/me` | 대기, 입장 상태 조회 |
| POST | `/drops/{dropId}/reservations` | 재고 예약 + 공통 주문 생성 |
| GET | `/drops/{dropId}/reservations/{reservationId}` | 예약 상태 조회 |
| POST | `/drops/{dropId}/reservations/{reservationId}/cancel` | 승인 시작 전 예약 취소 |

호출 순서:

GET 상품 상세 → GET 결제 예정 금액 → POST 대기열 등록 → GET 대기 상태 반복 조회 → 입장 허가 → POST 재고 예약/주문 생성 → PG 결제 → 공통 Payment 승인 API → 공통 Order 조회

결제하기 버튼은 대기열 진입 트리거다. 토스 결제창은 대기열 입장과 재고 예약이 성공한 뒤 띄운다.

## 운영자 API

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/admin/drops` | 전체 드롭 목록 |
| POST | `/admin/drops` | 드롭, SKU, 초기 재고 등록 |
| GET | `/admin/drops/{dropId}` | 운영자 상세 |
| PATCH | `/admin/drops/{dropId}` | 상품 설정 수정 |
| POST | `/admin/drops/{dropId}/publish` | 게시 확정 |
| GET | `/admin/drops/{dropId}/summary` | 재고, 판매 현황 |

## 공통 API 의존성 (팀 계약 미확정)

| Method | Endpoint (예시) | 기능 |
|---|---|---|
| GET | `/me/profile` | 회원 프로필, 기본 배송지 |
| GET | `/me/orders` | 내 주문 목록 |
| GET | `/orders/{orderId}` | 주문 상세 |
| POST | `/payments/confirm` | PG 승인 |
| GET | `/payments/orders/{orderId}` | PG 결과 확인 |
| POST | `/orders/{orderId}/refunds` | 전액 환불 요청 |
