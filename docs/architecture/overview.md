# 아키텍처 개요

## 1. 기술 결정 현황

| 영역 | 현재 결정 | 상태 |
|---|---|---|
| 애플리케이션 | Spring Boot 모듈러 모놀리스 | DECIDED |
| 언어 | Java 21, Spring Boot 4.1.x | DECIDED |
| 빌드 | Gradle 단일 모듈, 도메인별 패키지 | PROPOSED |
| DB | MySQL 8, 모든 도메인 공통 ([ADR-0002](../decisions/0002-common-conventions.md)) | DECIDED |
| PostgreSQL | 비교 실험 후 판단 | EXPERIMENTAL |
| 구매 대기열 | MySQL | DECIDED |
| Redis 대기열 | MySQL과 비교 후 판단 | EXPERIMENTAL |
| 비즈니스 이벤트 | Spring Event Listener ([ADR-0001](../decisions/0001-event-handling.md)) | DECIDED |
| Outbox, Kafka | ADR-0001 재검토 조건 충족 시 검토 | DEFERRED |
| PG | 토스페이먼츠 테스트 | DECIDED |
| 테스트 | JUnit 5, Testcontainers, k6 | DECIDED |
| 백엔드 배포 | 단일 EC2 + Docker | PROPOSED |
| DB 배포 | RDS MySQL | PROPOSED |
| 프론트 배포 | S3 + CloudFront | PROPOSED |
| 다중 서버 | 단일 서버 병목 확인 후 | EXPERIMENTAL |
| 실시간 경매 | WebSocket 후보 | PROPOSED |
| 팬 채팅 | MVP 제외 | DEFERRED |

모듈러 모놀리스의 핵심은 배포 단위 하나 안에서 도메인 소유권과 의존성을 명확히 나누는 것이다.

## 2. 패키지 구조와 규칙

```text
backend/src/main/java/com/restless/
├── auth/ member/
├── coupon/ ticketing/ auction/
├── drop/
│   ├── api/            # Controller, DTO
│   ├── application/    # 유스케이스, 트랜잭션 경계
│   ├── domain/         # 엔티티, 정책, 도메인 이벤트
│   └── infrastructure/ # Repository, 대기열 구현
├── order/ payment/ checkout/
├── statistics/
└── common/
```

1. 각 도메인은 자기 비즈니스 규칙과 영속성을 책임진다.
2. 다른 도메인의 Repository를 직접 참조하지 않는다.
3. 여러 도메인 상태를 한 DB 트랜잭션에서 바꿔야 하면 공통 Application Coordinator가 조정한다.
4. 대기열은 MySQL 구현으로 시작한다. Redis로 바꿀 때 구매 로직이 유지되도록 경계를 두되, 필요 이상으로 추상화하지 않는다.
5. 후속 작업 리스너는 그 기능을 소유한 모듈에 둔다.
6. Lombok은 `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@RequiredArgsConstructor`만 쓴다. `@Setter`, `@Data`는 쓰지 않는다. 상태 변경은 의도가 드러나는 메서드로 한다 (예: `reserve()`, `confirm()`).
7. 공통 오류 처리는 `common/error`에 둔다.

## 3. AWS 배포 (PROPOSED)

```text
Client
  ├── CloudFront → S3 정적 프론트엔드
  └── HTTPS → Nginx / EC2 → Spring Boot (Docker) → RDS MySQL
Spring Boot → Toss Payments HTTPS API
```

- 프론트엔드가 Next.js SSR을 필수로 쓰면 S3 정적 호스팅은 불가능하다. 정적 export 가능 여부를 먼저 확인한다.
- 백엔드 API도 HTTPS가 필요하다.
- RDS를 인터넷에 공개하지 않는다.
- 재고, 결제, 인증 API는 캐시 대상이 아니다.

## 4. 수평 확장 (EXPERIMENTAL)

단일 서버의 실제 한계를 먼저 측정한다. 확장이 필요하면 다중 EC2, ALB, 공통 대기열 상태, 중복 스케줄러 방지, 전체 DB 연결 수, WebSocket 서버 간 전달을 검토한다.

확장 목적이 처리량, 가용성, 무중단 배포 중 무엇인지 구분한다.
