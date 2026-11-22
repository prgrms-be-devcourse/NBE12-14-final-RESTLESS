# 경매·크레딧 모듈 지침

`com.restless.auction`과 `com.restless.credit`을 고칠 때 따른다. 크레딧은 1차에서 경매만 쓰므로 이 파일 하나로 함께 다룬다.
Codex는 레포 루트에서 실행하면 이 파일을 자동으로 읽지 않는다. 작업을 맡길 때 이 파일을 직접 가리킨다.

## 먼저 읽을 문서

- 계약: `docs/contracts/api/auction.md`. 요청·응답·오류 코드·SSE의 기준이다.
- 정책·불변식: `docs/domains/auction/`의 `policy.md`, `invariants.md`, `state-machines.md`, `transactions.md`
- 아직 정하지 않은 것: `docs/domains/auction/policy.md` 9절. 여기 있는 항목은 구현하지 않는다.

## 용어

- **확보(hold)**: 입찰 금액만큼 크레딧을 지갑에 묶는 것. 상태 `HELD`, 테이블 `credit_holds`. "예치", "잠금"이라고 쓰지 않는다. "잠금"은 DB 행 잠금만 뜻한다.
- **해제(release)**: 추월당한 입찰의 확보를 푸는 것. 상태 `RELEASED`.
- **차감(capture)**: 낙찰 때 확보한 크레딧을 실제로 빼는 것. 상태 `CAPTURED`.
- **입찰 상태**: `HIGHEST`, `OUTBID`, `WON`. DB에 저장하지 않고 계산한다 (`state-machines.md` 2절).
- **불변식(INV-AU-xx)**: `invariants.md`의 번호. 테스트 이름이나 주석에 붙인다.

## 규칙

- **모르면 멈춘다.** 문서에 없는 정책을 만나면 정하지 말고 멈춘 뒤, 무엇을 정해야 하는지 보고한다.
- 다른 도메인의 Repository를 쓰지 않는다. 회원은 `member.application.MemberService`로만 조회한다. 경매는 크레딧을 `credit.application.CreditWalletService`로만 쓴다.
- 잠금 순서는 경매 행 → 지갑 행(지갑 ID 오름차순)이다. 새 트랜잭션도 이 순서를 지킨다 (`transactions.md` 공통 규칙).
- 트랜잭션 안에서 외부 호출(HTTP, SSE 전송)을 하지 않는다. 실시간 전달은 커밋 후 이벤트(AFTER_COMMIT)로만 한다.
- 업무 거절(금액 부족, 이미 최고 입찰자 등)은 아무것도 바꾸지 않고 롤백한다.
- 오류 코드는 `ErrorCode`에 `AUCTION_`, `BID_`, `CREDIT_` 접두어로 추가하고, 계약 문서의 오류 표도 같이 고친다.
- 스키마는 새 마이그레이션 `V<YYYYMMDD>_<HHMM>__auction_<내용>.sql`로만 바꾼다. dev에 머지된 마이그레이션은 고치지 않는다.
- 공용 파일(`ErrorCode`, `SecurityConfig`, `GlobalExceptionHandler`, `application.yml`, `build.gradle`)은 꼭 필요한 만큼만 고치고, 고친 이유를 보고한다.
- 시각은 주입된 `Clock`으로 얻는다. 운영 코드에서 `Instant.now()`를 직접 부르지 않는다.
- 메트릭 태그에는 정해진 몇 개의 값(예: `action`, `stage`)만 넣는다. `auctionId`처럼 끝없이 늘어나는 값은 태그가 아니라 로그에 남긴다. 로그에 토큰·비밀번호를 남기지 않는다.
- 커밋과 push는 하지 않는다. 바꾼 파일과 이유를 보고하고, 커밋은 사람이 한다.

## 교훈 (버그에서 나온 규칙)

새 버그를 고치면 여기에 번호를 이어 붙이고, 그 버그를 잡는 테스트를 `docs/domains/auction/mutants.md`에 남긴다.

- **L-01** 시각으로 판단하는 트랜잭션은 경매 행 잠금을 얻은 **뒤에** `clock.instant()`를 읽는다. 먼저 읽으면 잠금을 기다리는 사이 시작된 경매를 "시작 전"으로 본다 (#142, `SellerAuctionLockOrderTest`).
- **L-02** 크레딧 확보·해제·차감은 `Propagation.MANDATORY`다. 별도 트랜잭션(`REQUIRES_NEW`)으로 바꾸면 종료 중 실패 때 "차감은 됐는데 결과는 없는" 상태가 남는다 (#140, INV-AU-13, `AuctionCloseFailureTest`).
- **L-03** 스케줄러는 경매마다 실패를 잡아 기록하고 다음 경매로 넘어간다. 예외를 밖으로 던지면 한 경매의 실패가 나머지 경매의 종료를 막는다 (#140, `AuctionCloseFailureTest`).
- **L-04** UNIQUE 위반을 잡아 "기존 행을 돌려주는" 코드는 그 저장과 같은 트랜잭션 안에 두지 않는다. 저장이 실패한 트랜잭션은 롤백 전용이 돼서, 기존 행을 읽어 정상 응답해도 커밋에서 500이 난다. 저장을 끝낸 뒤 새 조회로 읽는다 (#316, `CreditChargeService.create`, `CreditChargeApiTest` 같은 요청 키 동시 요청). 입찰처럼 위반을 409로 바꿔 던지기만 하면 같은 트랜잭션이어도 된다.

## 테스트

- 경매 테스트는 Testcontainers의 실제 MySQL로 돈다. 롤백·잠금·동시성은 DB를 가짜로 바꾼 단위 테스트로 확인하지 않는다.
- 데이터를 바꾸는 테스트는 끝에 `AuctionFixtures.assertInvariants()`로 불변식을 확인한다. 쿼리는 `backend/src/test/resources/harness/auction-invariants.sql`에 있다. 새 불변식은 이 파일에 `-- name:` 블록으로 추가한다.
- 테스트에서는 스케줄러가 꺼져 있다 (`build.gradle`의 `restless.auction.scheduler.enabled=false`). 스케줄러 동작을 볼 때는 `AuctionScheduler`를 테스트 안에서 직접 만들어 부른다 (`AuctionCloseFailureTest` 참고).
- 시간이 흐르는 상황은 실제로 기다리지 않고 테스트용 시계를 옮긴다 (`SellerAuctionLockOrderTest`의 `MutableClock` 참고).
- 고정 시계나 테스트용 시계로 JWT를 발급하지 않는다. 실제 날짜가 그 시각을 넘으면 토큰이 만료돼 401로 깨진다.
- 저장 전 시각과 DB에서 읽은 시각을 비교할 때는 1마이크로초 오차를 두거나, 처음부터 `truncatedTo(ChronoUnit.MICROS)`로 자른 값을 쓴다. MySQL은 나노초를 마이크로초로 반올림해 저장하고 Linux CI 시계는 나노초를 주므로, 그냥 비교하면 macOS에서만 통과한다 (드롭 HD-19, #119).
- 기본값이 있는 설정(`restless.auction.scheduler.enabled`, `stream.flush-interval-ms`, `scheduler.batch-size` 등)을 바꾸면, 그 기본값에 기대는 기존 테스트를 찾아 값을 명시한다. 특히 테스트에서 스케줄러를 끄는 `build.gradle`의 설정을 지우면 스케줄러가 테스트 중에 경매를 종료한다.
- 실패 상황은 `@MockitoSpyBean`으로 특정 호출만 예외를 던지게 해서 만든다. 이런 테스트는 Spring 컨텍스트를 하나 더 띄우므로 기존 테스트 클래스에 섞지 않고 따로 둔다.

## 검증 (`backend/`에서)

- 경매·크레딧 테스트: `./gradlew test --tests 'com.restless.auction.*' --tests 'com.restless.credit.*'`
- 전체 빌드: `./gradlew build`. 8~10분 걸리므로 작업 중에는 관련 테스트만 돌리고, PR 직전에 한 번 돌린다.
