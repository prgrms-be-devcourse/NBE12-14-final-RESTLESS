# 공통 API (나뉨)

이 문서는 2026-11-16에 나뉘었다. 예전 링크를 위해 남겨 둔 안내 페이지다. 새 링크는 아래 문서를 가리킨다.

| 예전 절 | 옮긴 곳 |
|---|---|
| 인증, 회원 (프로필, 탈퇴) | [auth.md](auth.md) |
| 인증 규칙 | [auth.md 인증 규칙](auth.md#인증-규칙) |
| 오류 응답 규칙 | [README 공통 규칙 3](README.md#공통-규칙) |
| 공연 | [ticketing.md](ticketing.md) |
| 주문, 결제 | [payment.md](payment.md) |

## 요청 횟수 제한

오류 응답은 [README 공통 규칙 3](README.md#공통-규칙)의 JSON 형식을 따른다.

| 상황 | 상태 | code | details |
|---|---|---|---|
| 요청 횟수 한도 초과 | 429 | `TOO_MANY_REQUESTS` | |

429 응답의 `Retry-After` 헤더는 재시도까지 기다릴 초를 올림하여 준다.
기본값은 아래와 같으며 `restless.ratelimit.*`로 변경할 수 있다.

| Method | Endpoint | 기준 | 한도 |
|---|---|---|---|
| POST | `/api/auth/login` | IP | 1분에 20회 |
| POST | `/api/auth/signup` | IP | 1분에 10회 |
| POST | `/api/auth/refresh` | IP | 1분에 30회 |
| POST | `/api/drops/{dropId}/queue/entries` | 로그인 사용자 ID | 10초에 5회 |
| GET | `/api/drops/{dropId}/queue/me` | 로그인 사용자 ID | 1초에 5회 |

첫 요청부터 고정 길이의 창을 센다. 규칙별로 분리하며 사용자 한도는 dropId와 무관하게 합산한다.
IP는 `request.getRemoteAddr()` 기준이며 `X-Forwarded-For`를 사용하지 않는다.
429로 거절된 상태 조회는 heartbeat를 갱신하지 않는다. 위 5개 외의 경로는 제한하지 않는다.
`restless.ratelimit.enabled=false`면 제한을 끈다. perf 프로필과 일반 테스트에서는 끈다.
