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
| GET | `/api/me/profile` | 내 프로필, 기본 배송지 | 예정 |
| PATCH | `/api/me/profile` | 프로필, 기본 배송지 수정 | 예정 |

## 인증 규칙

- **Access Token**: JWT(HS256), 1시간. 로그인 응답 본문으로 주고, 요청 때 `Authorization: Bearer <accessToken>` 헤더로 보낸다.
- **Refresh Token**: 7일. `refresh_token` HttpOnly 쿠키(`Secure`, `SameSite=Strict`, `Path=/api/auth`)로만 오간다. 프론트는 fetch에 `credentials: 'include'`를 준다. 재발급하면 새 토큰으로 바뀌고, 이미 쓴 토큰이 다시 오면 그 로그인의 토큰을 모두 폐기한다.
- **로그인 필요**: 아래 공개 경로를 뺀 모든 API. 토큰이 없거나 만료·위조면 401 `UNAUTHORIZED`.
- **공개 경로**: 가입·로그인·재발급·로그아웃, 공연·회차 좌석·드롭·경매·팬미팅 상세 조회(GET), `/actuator/health`. 로그인 없이 볼 조회 API를 만들면 `SecurityConfig.PUBLIC_GET_PATHS`에 추가한다.
- **관리자**: `/api/admin/**`는 `role = ADMIN`만. 아니면 403 `FORBIDDEN`. 관리자 기능은 이 경로 아래로 만든다(컨트롤러에서 권한을 다시 검사하지 않는다). 가입하면 USER이고, ADMIN은 DB에서 지정한다.
- **로그인 회원 ID**: 컨트롤러 파라미터에 `@LoginMember Long userId`를 쓴다. 요청 본문으로 회원 ID를 받지 않는다.
- **현재 시각**: `Instant.now()` 대신 `Clock`을 주입받아 `clock.instant()`로 읽는다(`common/config/ClockConfig`). 테스트에서 시각을 바꿀 수 있다.

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
