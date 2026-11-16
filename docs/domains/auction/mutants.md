# Auction 버그 목록

> 관련 코드: `backend/src/main/java/com/restless/credit/application/CreditWalletService.java`, `backend/src/main/java/com/restless/auction/application/AuctionScheduler.java`, `backend/src/main/java/com/restless/auction/application/SellerAuctionService.java`
>
> 마지막 확인: 2026-11-16 (A1~A3을 자동 검사 목록과 대조)

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

## 확인 기록

| ID | 확인한 때 | 버그를 넣었을 때 | 되돌린 뒤 |
|---|---|---|---|
| A1 | #140 작업 중 | 테스트 실패. 결과 저장이 롤백돼도 차감은 남음 | 통과 |
| A2 | #140 작업 중 | 테스트 실패. 뒤 경매를 처리하지 못하고 예외가 밖으로 나옴 | 통과 |
| A3 | #142 작업 중 (수정 전 코드가 곧 이 버그) | 두 테스트 실패. 시작된 경매가 수정·삭제됨 | 통과 |

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
