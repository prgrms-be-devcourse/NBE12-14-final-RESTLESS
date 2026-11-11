# 공통 API (초안)

- 담당: TBD
- 상태: PROPOSED. 드롭과 다른 도메인이 기대하는 기능을 모은 초안이다. 인증 방식(JWT, 세션)은 미정이다.

## 인증, 회원

| Method | Endpoint | 기능 |
|---|---|---|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/me/profile` | 내 프로필, 기본 배송지 |
| PATCH | `/api/me/profile` | 프로필, 기본 배송지 수정 |

## 공연

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/concerts` | 공연 목록 |
| GET | `/api/concerts/{concertId}` | 공연 상세 (연결된 드롭, 경매 포함 여부는 미정) |

공연 회차 API는 티켓팅 문서에 있다.

## 주문, 결제

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/me/orders` | 내 주문 목록 |
| GET | `/api/orders/{orderId}` | 주문 상세 |
| POST | `/api/payments/confirm` | 토스 결제 승인 (paymentKey, orderId, amount) |
| GET | `/api/payments/orders/{orderId}` | 결제 결과 확인 |
| POST | `/api/orders/{orderId}/refunds` | 전액 환불 요청 |

티켓팅이 공통 주문을 거치는지는 [erd.md 4절](../../architecture/erd.md)의 확인 항목이다.
