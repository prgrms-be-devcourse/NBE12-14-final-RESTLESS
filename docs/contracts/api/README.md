# API 계약

- 상태: Draft. 각 도메인 담당자가 정본을 관리한다.
- 경로: `/api/...` ([ADR-0002](../../decisions/0002-common-conventions.md))

| 문서 | 담당 | 상태 |
|---|---|---|
| [common.md](common.md) | TBD | 드롭 요구사항 기준 초안 |
| [ticketing.md](ticketing.md) | 송혜민 | 원본 문서에서 취합 |
| [drop.md](drop.md) | 이태호 | 원본 |
| [fanmeeting.md](fanmeeting.md) | 홍승연 | 원본 문서에서 취합 |
| [auction.md](auction.md) | 천종원 | 1차 구현 기준 (PROPOSED) |

## 공통 규칙 (PROPOSED)

1. 회원 식별 값은 요청 본문이 아니라 인증 정보에서 꺼낸다.
2. 자원을 만드는 경쟁 요청(좌석 선점, 재고 예약, 입찰)은 `Idempotency-Key` 헤더를 받는다.
3. 오류 응답 형식은 아래로 통일한다. 티켓팅의 `details`와 쿠폰의 `traceId`, `timestamp`를 합친 것이다.

```json
{
  "code": "SEAT_UNAVAILABLE",
  "message": "선택한 좌석 중 이미 선점된 좌석이 있습니다.",
  "details": { "seatIds": [11] },
  "traceId": "...",
  "timestamp": "2026-11-20T11:00:01Z"
}
```

4. 오류 코드는 `도메인_상황` 대문자 스네이크 케이스로 쓴다. 정상 거절(품절, 이미 받음)은 409 또는 422, 서버 오류는 5xx로 구분한다.
