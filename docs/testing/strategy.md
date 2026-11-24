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
| 동시성 테스트 (드롭·결제) | `./gradlew test --tests 'com.restless.drop.*' --tests 'com.restless.payment.*'` (재고 1개에 100명, 같은 키 동시 요청, 입장 허가 작업 동시 실행, 승인 동시 요청 등) |
| 부하 테스트 시드 (perf DB) | 레포 루트에서 `perf/seed/seed.sh --scale base --profile perf` ([perf/seed](../../perf/seed/README.md)) |
| 부하 테스트 | [perf/README](../../perf/README.md)의 k6 시나리오. 드롭은 [성능 개선 계획](drop-performance-plan.md)의 반복을 따르고, 측정은 [실행 절차](drop-performance-procedure.md)대로 한다 |

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
| `restless.ratelimit.enabled` | `false` | 같은 IP로 반복하는 기존 API 테스트가 요청 제한에 걸리지 않게 한다. 제한 검증 테스트는 `true`를 명시한다 |
| `user.timezone` | `UTC` | JDBC `Timestamp`로 넣은 테스트 데이터와 JPA `TIMESTAMP_UTC` 컬럼이 호스트 시간대와 상관없이 같은 시각을 가리키게 한다 |
| `spring.test.context.cache.maxSize` | `4` | 캐시된 Spring 컨텍스트마다 DB 연결이 남는다. 전체 실행 중 MySQL 컨테이너가 메모리 부족(OOMKilled, exit 137)으로 죽은 적이 있어 개수를 제한한다 |

테스트 작성 시 주의:

- 드롭 입장 제어는 기본으로 켜져 있다(`restless.drop.queue.required=true`). 입장 제어를 검증하지 않는 드롭 테스트는 `restless.drop.queue.required=false`를 명시한다.
- 결제 흐름 테스트가 가짜 결제 결과 Handler를 쓸 때는 실제 Handler와 같은 빈 이름(예: `dropPaymentResultHandler`)으로 등록하고 그 테스트 클래스에만 `spring.main.allow-bean-definition-overriding=true`를 준다. 같은 결제 대상에 Handler가 둘이면 운영 코드의 중복 검사가 컨텍스트를 실패시킨다.

## 7. PR 전 확인

- 최신 `dev`를 합친 상태에서 `./gradlew build`를 돌린다. CI는 PR 브랜치가 아니라 "dev에 합친 결과"를 빌드한다. 각자 통과한 두 변경이 합쳐서 깨질 수 있다 (예: enum 끝의 `;`, 기본값이 바뀐 설정).
- 다른 PR 위에 쌓은 PR은 앞 PR이 머지되면 dev를 다시 합치고 빌드한다.

## 7-1. CI 테스트 범위

- PR과 dev push는 영향받는 테스트만 돌릴 수 있다. 규칙은 `tools/ci/select-backend-tests.py`에 있다.
- 도메인 하나만 바뀌면 그 도메인, 그 도메인을 쓰는 도메인, 그 도메인이 구현한 인터페이스를 가진 모듈의 테스트를 돌린다. ArchUnit과 기동 smoke test는 항상 돈다.
- 공유 모듈(common, auth, member, order, payment, concert), migration, Spring 설정, 공용 테스트 fixture, Gradle과 CI 설정이 바뀌면 전체를 돈다. 분류할 수 없는 경로, 변경 목록 조회 실패도 전체다.
- dev push는 마지막 커밋이 아니라 push 전체 범위(before..after)를 본다. main push는 항상 전체다.
- 컴파일과 패키징은 범위와 상관없이 항상 한다. 문서만 바뀌면 백엔드 build를 건너뛴다.
- dev 전체 테스트는 매일 03:00(KST) 정기 실행과 수동 실행으로 돈다. 전체 기준은 1195개다.
- dev 이미지는 영향 범위 테스트만 통과해도 발행된다. 정기 전체 테스트에서만 잡히는 회귀는 이미지 발행 뒤에 발견될 수 있다.
- 실행 범위는 CI 요약의 Backend test scope와 artifact의 `test-scope.txt`에 남는다. 다른 모듈에 영향이 있을 것 같으면 로컬에서 `./gradlew build`를 돌린다.

## 8. 원칙

- PG 대량 부하 테스트에는 실제 토스 API가 아니라 Fake/Mock PG를 쓴다.
- 테스트를 끄거나 단언을 약하게 해서 빌드를 통과시키지 않는다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
