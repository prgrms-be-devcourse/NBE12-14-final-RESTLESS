# 드롭 모듈 지침

`com.restless.drop`을 고칠 때 루트 `AGENTS.md`에 더해 따른다. 여기에는 드롭에만 해당하는 것만 둔다.
Codex는 레포 루트에서 실행하면 이 파일을 자동으로 읽지 않는다 (2026-11-14 확인). 하네스 지시서가 이 파일을 직접 가리킨다.

## 먼저 읽을 문서

- 계약: `docs/contracts/api/drop.md`. 요청·응답·오류 코드의 기준이고, 수용 테스트가 이 계약으로 판정한다.
- 정책·불변식: `docs/domains/drop/` 의 `policy.md`, `invariants.md`, `state-machines.md`, `transactions.md`, `data-model.md`
- 결제 연결: `docs/decisions/0003-payment-module-boundary.md`

## 용어

- **재고 점유**: 결제 전에 SKU 재고 1개를 잠깐 확보하는 것. 상태 `RESERVED`, 테이블 `drop_reservations`. "찜", "재고 예약"이라고 쓰지 않는다.
- **불변식(INV-xx)**: `docs/domains/drop/invariants.md`의 번호. 테스트 이름이나 주석에 붙인다.

## 규칙

- 다른 도메인의 Repository를 쓰지 않는다. 주문은 `backend/src/main/java/com/restless/order/application/OrderService.java`(쓰기는 호출자 트랜잭션 안에서), 결제는 `backend/src/main/java/com/restless/payment/application/payable/PaymentResultHandler.java` 계약으로만 연결한다. 주문 상세의 결제 대상은 `backend/src/main/java/com/restless/drop/application/DropOrderPayableResolver.java`가 알려 준다. ArchUnit `OwnedModuleBoundaryTest`가 검사한다.
- 재고·점유·주문 상태는 트랜잭션 안에서 커밋한다. PG 호출 중에는 트랜잭션을 열어 두지 않는다. Outbox·Kafka를 쓰지 않는다.
- 공용 파일(`ErrorCode`, `SecurityConfig`, `application.yml`, `build.gradle`)은 꼭 필요한 만큼만 고친다. 오류 코드는 `DROP_` 접두어로 `ErrorCode` 맨 끝에 추가한다.
- 스키마는 새 마이그레이션 `V<YYYYMMDD>_<HHMM>__drop_<내용>.sql`로만 바꾼다. dev에 머지된 마이그레이션은 고치지 않는다.
- 시각은 주입된 `Clock`으로 얻는다. 테스트에서 고정 시계로 JWT를 발급하지 않는다 (시간이 지나면 401로 깨진다).
- 테스트에서 저장 전 시각과 DB에서 읽은 시각을 비교할 때는 1마이크로초 오차를 둔다. MySQL은 나노초를 마이크로초로 반올림해 저장하고, Linux CI 시계는 나노초를 준다. 버림 비교는 macOS에서만 통과한다 (HD-19, #119).
- 메트릭 태그에 ID처럼 끝없이 늘어나는 값을 넣지 않는다. 로그에 시크릿이나 `paymentKey`를 남기지 않는다.
- 기본값이 있는 설정(예: `restless.drop.queue.required=true`)을 바꾸면, 그 기본값에 기대는 기존 테스트를 찾아 값을 명시한다.
- 문서에 없는 정책은 멈추지 말고 문서에 가장 가까운 쪽으로 정한다. 정한 내용은 같은 PR에서 `policy.md`의 해당 절에 `PROPOSED`로 남긴다. 하네스 단계에서는 Codex가 보고서의 "대신 정한 것"에 적고 Claude가 옮긴다.

## 검증 (`backend/`에서)

- 드롭 테스트: `./gradlew test --tests 'com.restless.drop.*'`
- 모듈 경계: `./gradlew test --tests 'com.restless.architecture.*'`
- 최종 판정 (레포 루트에서): `bash tools/harness/harness-check.sh`. 규칙, 문서, dev와 합친 전체 빌드를 본다.
