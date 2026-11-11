# 테스트 전략

## 1. 실행 명령

백엔드 명령은 `backend/`, 프론트엔드 명령은 `frontend/`에서 실행한다.

| 목적 | 명령 |
|---|---|
| 백엔드 빌드 + 전체 테스트 (CI와 동일) | `./gradlew build` |
| 백엔드 테스트만 | `./gradlew test` |
| 특정 테스트 | `./gradlew test --tests 'com.restless.drop.*'` |
| 프론트엔드 빌드 (CI와 동일) | `npm run build` |
| 아키텍처 검사 | TBD |
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

## 5. 원칙

- PG 대량 부하 테스트에는 실제 토스 API가 아니라 Fake/Mock PG를 쓴다.
- 테스트를 끄거나 단언을 약하게 해서 빌드를 통과시키지 않는다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
