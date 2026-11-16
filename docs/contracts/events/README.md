# 이벤트 계약

> 마지막 확인: 2026-11-16 (`backend/src/main/java`의 이벤트 클래스와 대조)

서버 안에서 한 모듈이 "일어난 일"을 알리면 다른 모듈이 받아 후속 작업을 하는 약속이다. HTTP API와 달리 프론트는 보지 않는다.

처리 방식은 [ADR-0001](../../decisions/0001-event-handling.md)을 따른다.
- Spring 애플리케이션 이벤트를 쓰고, 후속 작업은 커밋 뒤(`@TransactionalEventListener(phase = AFTER_COMMIT)`)에 한다.
- 재고, 주문, 결제 같은 핵심 상태는 이벤트에 맡기지 않는다. 같은 트랜잭션에서 직접 커밋한다.
- 결제 결과는 이벤트가 아니라 결제 모듈이 도메인 Handler를 직접 부른다 ([ADR-0003](../../decisions/0003-payment-module-boundary.md)).
- Outbox, Kafka는 쓰지 않는다.

## 코드에 있는 이벤트

| 이벤트 | 발행 | 받는 쪽 | 문서 |
|---|---|---|---|
| `MemberWithdrawnEvent(userId)` | 회원 탈퇴 트랜잭션 | 인증: 그 회원의 Refresh Token 폐기 | [auth.md 탈퇴](../api/auth.md#탈퇴) |
| `AuctionChangedEvent(auctionId, revision)` | 입찰, 시작, 종료, 수정, 삭제 커밋 뒤 | 경매 실시간 스트림(SSE) 전송. 경매 모듈 안에서만 씀 | [경매 transactions.md](../../domains/auction/transactions.md) |

## 문서

| 문서 | 상태 |
|---|---|
| [drop.md](drop.md) | 후보. 코드에 드롭 이벤트는 아직 없다 |
| [fanmeeting.md](fanmeeting.md) | 초안. 발행 이벤트(2절)는 P2이고 아직 코드에 없다 |

다른 모듈이 받는 이벤트를 새로 만들면 이 표와 해당 도메인 문서에 추가한다.
