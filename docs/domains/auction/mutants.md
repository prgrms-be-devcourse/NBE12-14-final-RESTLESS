# Auction 버그 목록

> 관련 코드: `backend/src/main/java/com/restless/credit/application/CreditWalletService.java`, `backend/src/main/java/com/restless/credit/application/CreditChargePaymentResultHandler.java`, `backend/src/main/java/com/restless/credit/application/CreditChargeService.java`, `backend/src/main/java/com/restless/auction/application/AuctionScheduler.java`, `backend/src/main/java/com/restless/auction/application/SellerAuctionService.java`
>
> 마지막 확인: 2026-11-21 (#316 크레딧 충전 C1~C13 추가)

경매 테스트가 **실제로 잡아야 하는 버그**를 적은 목록이다. 운영 코드에 버그를 하나씩 일부러 넣었을 때 지정한 테스트가 실패해야 한다. 실패하지 않으면 그 테스트는 이름과 달리 아무것도 지키지 못하는 것이다.

- 목적: "이 테스트는 왜 이렇게 생겼나"의 근거를 남기고, 같은 검사를 언제든 다시 해 볼 수 있게 한다.
- 각 버그는 실제 사고나 코드 리뷰에서 나왔다. 버그에서 나온 규칙은 [경매 지침](../../../backend/src/main/java/com/restless/auction/AGENTS.md)의 "교훈"(L-xx)에 있다.
- 드롭 하네스의 버그 목록과 같은 생각이다. 하네스 도구는 각자 PC에 두므로(git-ignore, #151), 이 문서가 팀이 함께 보는 정본이다. 로컬 하네스가 있으면 같은 목록을 자동으로 넣어 볼 수 있고, 없으면 아래 방법대로 손으로 넣어 본다.

## 목록

| ID | 넣을 버그 | 바꿀 곳 | 잡아야 할 테스트 | 지키는 것 | 근거 |
|---|---|---|---|---|---|
| A1 | 크레딧 차감을 별도 트랜잭션으로 커밋 | `CreditWalletService.capture`의 `@Transactional(propagation = Propagation.MANDATORY)`를 `Propagation.REQUIRES_NEW`로 | `AuctionCloseFailureTest` `차감_뒤_결과_저장에서_실패하면_차감까지_함께_롤백되고_다시_종료하면_한_번만_차감된다_INV_AU_13` | INV-AU-13, L-02 | #140 |
| A2 | 스케줄러가 경매 하나의 실패를 밖으로 던짐 | `AuctionScheduler.process`의 `catch` 블록 끝(메트릭 증가 다음 줄)에 `throw e;` 추가 | `AuctionCloseFailureTest` `스케줄러에서_경매_하나의_종료가_실패해도_나머지는_종료되고_실패한_경매는_다음_주기에_종료된다` | A-06, L-03 | #140 |
| A3 | 수정·삭제가 잠금 전에 시각을 읽음 | `SellerAuctionService.update`, `delete`에서 `Instant now = clock.instant();`를 `lockOwned(userId, auctionId)` 위로 | `SellerAuctionLockOrderTest` 두 테스트 (수정, 삭제) | TX-AU-5, L-01 | #142 |
| C1 | 같은 충전을 두 번 지급 | `CreditChargePaymentResultHandler.onApproved`의 `PAID` 확인을 없앰 | `CreditChargeHandlerTest` 같은 승인 두 번, C-03 동시 확정, 다른 결제의 승인 | INV-AU-12, 16 | #316 |
| C2 | 결과 불명확이면 지급 | `CreditChargePaymentResultHandler` `onUnknown`에서 지급 | `CreditChargeApiTest` C-04, C-05, `CreditChargeHandlerTest` 실패·불명확 통지 | AGENTS 4절 (불명확은 실패도 성공도 아님) | #316 |
| C3 | 결제 실패면 지급 | `CreditChargePaymentResultHandler` `onFailed`에서 지급 | `CreditChargeApiTest` C-05, C-06 | INV-AU-16 | #316 |
| C4 | 충전 주문을 잠그지 않고 읽음 | `CreditChargePaymentResultHandler` `lock`을 `findById`로 | `CreditChargeHandlerTest` C-03 동시 확정 | INV-AU-12 | #316 |
| C5 | 기한 지난 승인을 거절 | `CreditChargePaymentResultHandler` `onApproved`에 기한 확인 추가 | `CreditChargeHandlerTest` C-08 | 정책 7.1절 (돈을 받고 거절하지 않음) | #316 |
| C6 | 충전된 주문도 결제 대상으로 줌 | `CreditChargePaymentResultHandler` `load`의 `READY` 거름을 없앰 | `CreditChargeHandlerTest` PAID나 없는 주문은 empty | INV-AU-12 | #316 |
| C7 | 결제 대상의 회원을 잘못 줌 | `CreditChargePaymentResultHandler` `load`의 회원 ID를 다른 값으로 | `CreditChargeApiTest` 다수 (결제 준비가 404) | 소유자 검사 | #316 |
| C8 | 내역 없이 지갑만 늘림 | `CreditWalletService.charge`의 `transactions.save` 삭제 | 불변식 INV-AU-15, 16 (`assertInvariants`, `assertGrantedOnce`) | INV-AU-15, 16 | #316 |
| C9 | 충전 금액과 다른 금액을 지급 | `CreditChargePaymentResultHandler` `wallets.charge`에 금액 + 1,000 | `assertGrantedOnce`를 쓰는 테스트 다수 | INV-AU-16 | #316 |
| C10 | 승인 금액 확인을 없앰 | `CreditChargePaymentResultHandler` 금액 불일치 거절을 없앰 | `CreditChargeHandlerTest` 금액이 다른 승인은 거절 | INV-AU-17 | #316 |
| C11 | 지급을 별도 트랜잭션으로 커밋 | `CreditWalletService.charge`를 `Propagation.REQUIRES_NEW`로 | `CreditChargeHandlerTest` 결제 트랜잭션이 롤백되면 지급도 롤백 | INV-AU-16, L-02 | #316 |
| C12 | 충전 주문 생성을 한 트랜잭션으로 감쌈 | `CreditChargeService.create`에 `@Transactional` | `CreditChargeApiTest` 같은 요청 키 동시 요청 | INV-AU-12, L-04 | #316 |
| C13 | 승인 시작에서 기한을 보지 않음 | `CreditChargePaymentResultHandler` `onApprovalStarting`의 기한 확인을 없앰 | `CreditChargeHandlerTest` 기한 뒤 승인 시작은 거절 | 정책 7.1절 | #316 |

## 확인 기록

| ID | 확인한 때 | 버그를 넣었을 때 | 되돌린 뒤 |
|---|---|---|---|
| A1 | #140 작업 중 | 테스트 실패. 결과 저장이 롤백돼도 차감은 남음 | 통과 |
| A2 | #140 작업 중 | 테스트 실패. 뒤 경매를 처리하지 못하고 예외가 밖으로 나옴 | 통과 |
| A3 | #142 작업 중 (수정 전 코드가 곧 이 버그) | 두 테스트 실패. 시작된 경매가 수정·삭제됨 | 통과 |
| C1~C13 | #316 검증 (2026-11-21) | 13개 모두 지정한 테스트 실패. C11은 처음에 아무 테스트도 실패하지 않아 "결제 트랜잭션이 롤백되면 지급도 롤백" 테스트를 추가한 뒤 잡힘. C12는 Codex 구현에 실제로 있던 버그(L-04) | 통과 |

2026-11-16: 로컬 하네스의 자동 검사로 A1~A3을 dev `ce5de15` 기준에서 다시 넣어 봤다. 3개 모두 지정한 테스트가 잡았다 (A3은 수정·삭제 두 테스트 모두 실패).

## 넣어 보는 방법

1. 위 "바꿀 곳"대로 운영 코드 한 곳만 바꾼다. 버그는 한 번에 하나만 넣는다.
2. 잡아야 할 테스트만 돌린다. 예: `./gradlew test --tests 'com.restless.auction.AuctionCloseFailureTest'` (`backend/`에서)
3. 테스트가 **실패하는지** 확인한다. 통과하면 테스트가 이 버그를 못 잡는 것이므로 테스트를 보강한다.
4. 운영 코드를 원래대로 되돌리고 `git status`로 남은 변경이 없는지 확인한다. 버그를 넣은 코드는 커밋하지 않는다.

## 추가하는 규칙

- 경매·크레딧 버그를 고치면, 그 버그를 다시 넣었을 때 실패하는 테스트를 만들고 여기에 한 줄 추가한다.
- 새 테스트를 만들 때 "이 테스트가 없으면 어떤 버그가 통과하나"가 분명하면 여기에 한 줄 추가한다.
- 코드가 바뀌어 "바꿀 곳"이 사라지면 그 행을 새 위치로 고치거나, 더 의미가 없으면 지운다.
- ID는 다시 쓰지 않는다.
