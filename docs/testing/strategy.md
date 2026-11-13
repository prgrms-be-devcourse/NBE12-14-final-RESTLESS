# 테스트 전략

## 1. 실행 명령

백엔드 명령은 `backend/`, 프론트엔드 명령은 `frontend/`에서 실행한다.

| 목적 | 명령 |
|---|---|
| 백엔드 빌드 + 전체 테스트 (CI와 동일) | `./gradlew build` |
| 백엔드 테스트만 | `./gradlew test` |
| 특정 테스트 | `./gradlew test --tests 'com.restless.drop.*'` |
| 프론트엔드 빌드 (CI와 동일) | `npm run build` |
| 아키텍처 검사 (모듈 경계) | `./gradlew test --tests 'com.restless.architecture.*'` |
| 드롭 수용 테스트 (블랙박스) | `./gradlew test --tests 'com.restless.drop.acceptance.*'` |
| 동시성 테스트 | TBD |
| 부하 테스트 | TBD |

## 2. 기술

JUnit 5, Spring Boot Test, MySQL Testcontainers, Mockito, k6. 필요하면 Redis Testcontainers.

## 3. 테스트 유형

| 유형 | 목적 |
|---|---|
| Concurrency | 재고, 주문 경쟁 조건 검증 |
| Load | 목표 부하에서 지속 성능 |
| Stress | 한계와 병목 탐색 |
| Spike | 판매 시작 순간 대량 유입 |
| Fault Injection | 장애 중 거래 정합성 |
| Soak | 장시간 자원, 성능 안정성 |

## 4. 필수 동시성 테스트 (Drop)

- 재고 1개에 100명 동시 예약
- 재고 1,000개에 2,000명 동시 예약
- 같은 사용자가 여러 탭에서 구매
- 같은 멱등성 키 중복 전송
- SKU별 재고 경쟁
- 예약 만료와 PG 승인 시작 경합
- 환불 완료와 신규 구매 경합
- 다중 서버에서 중복 입장, 중복 작업

각 테스트는 관련 불변식 ID([invariants.md](../domains/drop/invariants.md))를 명시한다.

## 5. 드롭 수용 테스트와 불변식 SQL

`backend/src/test/java/com/restless/drop/acceptance/`는 드롭 API 계약([drop.md](../contracts/api/drop.md))의 완료 기준이다.

- 블랙박스: 드롭 구현 클래스를 import하지 않고 실제 HTTP 서버(임의 포트)와 DB만 보고 판정한다. 구현이 바뀌어도 테스트는 바뀌지 않는다.
- 매 테스트가 끝나면 `backend/src/test/resources/harness/drop-invariants.sql`의 불변식(초과 판매, 재고 합계, 중복 주문 등)을 전부 검사한다. 위반이 1건이라도 있으면 실패다.
- 시간에 따라 도는 작업(만료, 입장)은 빈 이름으로 직접 실행한다: `dropReservationExpiryJob.runOnce()`, `dropQueueAdmissionJob.runOnce()`.
- 테스트가 계약과 다르면 테스트를 고치지 말고 계약과 테스트를 함께 바꾸는 PR을 따로 연다.

## 6. 테스트 실행 환경

`backend/build.gradle`의 `test` 작업이 정하는 JVM 설정이다. 모든 테스트에 적용된다.

| 설정 | 값 | 이유 |
|---|---|---|
| `restless.auction.scheduler.enabled`, `restless.payment.reconcile.enabled`, `restless.drop.expiry.enabled`, `restless.drop.queue.scheduler.enabled`, `restless.fanmeeting.worker.enabled` | `false` | 주기 작업이 다른 테스트의 데이터 준비와 겹치지 않게 한다. 작업 빈은 남아 있으므로 해당 테스트가 직접 호출하거나 `@SpringBootTest(properties)`로 다시 켠다 |
| `user.timezone` | `UTC` | JDBC `Timestamp`로 넣은 테스트 데이터와 JPA `TIMESTAMP_UTC` 컬럼이 호스트 시간대와 상관없이 같은 시각을 가리키게 한다 |
| `spring.test.context.cache.maxSize` | `4` | 캐시된 Spring 컨텍스트마다 DB 연결이 남는다. 전체 실행 중 MySQL 컨테이너가 메모리 부족(OOMKilled, exit 137)으로 죽은 적이 있어 개수를 제한한다 |

테스트 작성 시 주의:

- 드롭 대기열은 기본으로 켜져 있다(`restless.drop.queue.required=true`). 대기열을 검증하지 않는 드롭 테스트는 `restless.drop.queue.required=false`를 명시한다.
- 결제 흐름 테스트가 가짜 결제 결과 Handler를 쓸 때는 실제 Handler와 같은 빈 이름(예: `dropPaymentResultHandler`)으로 등록하고 그 테스트 클래스에만 `spring.main.allow-bean-definition-overriding=true`를 준다. 같은 결제 대상에 Handler가 둘이면 운영 코드의 중복 검사가 컨텍스트를 실패시킨다.

## 7. PR 전 확인

- 최신 `dev`를 합친 상태에서 `./gradlew build`를 돌린다. CI는 PR 브랜치가 아니라 "dev에 합친 결과"를 빌드한다. 각자 통과한 두 변경이 합쳐서 깨질 수 있다 (예: enum 끝의 `;`, 기본값이 바뀐 설정).
- 다른 PR 위에 쌓은 PR은 앞 PR이 머지되면 dev를 다시 합치고 빌드한다.

## 8. 원칙

- PG 대량 부하 테스트에는 실제 토스 API가 아니라 Fake/Mock PG를 쓴다.
- 테스트를 끄거나 단언을 약하게 해서 빌드를 통과시키지 않는다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
