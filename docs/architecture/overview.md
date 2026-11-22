# 아키텍처 개요

## 1. 기술 결정 현황

| 영역 | 현재 결정 | 상태 |
|---|---|---|
| 애플리케이션 | Spring Boot 모듈러 모놀리스 | IMPLEMENTED |
| 언어 | Java 21, Spring Boot 4.1.x | IMPLEMENTED |
| 빌드 | Gradle 단일 모듈, 도메인별 패키지 | PROPOSED |
| DB | MySQL 8, 모든 도메인 공통 ([ADR-0002](../decisions/0002-common-conventions.md)) | IMPLEMENTED |
| PostgreSQL | 비교 실험 후 판단 | EXPERIMENTAL |
| 구매 입장 제어 | MySQL | IMPLEMENTED |
| Redis 입장 제어 | MySQL과 비교 후 판단 | EXPERIMENTAL |
| 비즈니스 이벤트 | Spring Event Listener ([ADR-0001](../decisions/0001-event-handling.md)) | DECIDED |
| Outbox, Kafka | ADR-0001 재검토 조건 충족 시 검토 | DEFERRED |
| PG | 토스페이먼츠 테스트 | IMPLEMENTED |
| 테스트 | JUnit 5, Testcontainers, k6 | IMPLEMENTED |
| 백엔드 배포 | 단일 EC2 + Docker | IMPLEMENTED |
| DB 배포 | RDS MySQL | IMPLEMENTED |
| 프론트 배포 | S3 + CloudFront | PROPOSED |
| 다중 서버 | 단일 서버 병목 확인 후 | EXPERIMENTAL |
| 요청 횟수 제한 | 앱 인터셉터와 서버 메모리 고정 창 카운터. 서버가 2대 이상이면 한도가 서버별로 나뉜다 ([ADR-0004](../decisions/0004-request-rate-limit.md)) | IMPLEMENTED |
| 실시간 경매 | SSE (서버 → 클라이언트). 입찰은 일반 POST ([경매 정책 8절](../domains/auction/policy.md)) | IMPLEMENTED |
| 팬 채팅 | MVP 제외 | DEFERRED |

모듈러 모놀리스의 핵심은 배포 단위 하나 안에서 도메인 소유권과 의존성을 명확히 나누는 것이다.

## 2. 패키지 구조와 규칙

```text
backend/src/main/java/com/restless/
├── auth/ member/
├── fanmeeting/ ticketing/ auction/ credit/
├── concert/
├── drop/
│   ├── api/            # Controller
│   ├── application/    # 유스케이스, 트랜잭션 경계, 입장 제어·만료 작업, 결제 Handler, DTO(dto/)
│   ├── domain/         # 엔티티, 상태 전이
│   └── infrastructure/ # Repository
├── order/ payment/
├── statistics/
└── common/
```

1. 각 도메인은 자기 비즈니스 규칙과 영속성을 책임진다.
2. 다른 도메인의 Repository를 직접 참조하지 않는다.
3. 결제와 도메인 상태를 한 DB 트랜잭션에서 바꿀 때는 결제 모듈이 트랜잭션을 열고 도메인의 `PaymentResultHandler`를 동기 호출한다 ([ADR-0003](../decisions/0003-payment-module-boundary.md)). 주문은 도메인이 `OrderService`로 같은 트랜잭션 안에서 만들고 바꾼다. 별도 Coordinator는 두지 않는다. 주문 상세는 두 가지 읽기 연결을 쓴다. 결제 대상은 도메인이 `order.application.OrderPayableResolver`를 구현해 알려 준다. 최근 결제는 결제 모듈의 `PaymentLookup`으로만 읽는다. 주문 모듈이 결제 모듈에서 쓸 수 있는 타입은 이것 하나다 (ArchUnit `OwnedModuleBoundaryTest`, #114).
4. 입장 제어는 MySQL 구현으로 시작한다. Redis로 바꿀 때 구매 로직이 유지되도록 경계를 두되, 필요 이상으로 추상화하지 않는다.
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
- 백엔드 이미지는 `backend/Dockerfile`로 만든다. dev에서 CI가 통과한 커밋을 `.github/workflows/backend-image.yml`이 amd64로 빌드해 GHCR `nbe12-14-final-restless-backend`에 `sha-<커밋 12자리>` 태그로 올린다. `dev` 태그는 그 커밋이 아직 dev의 최신 커밋일 때만 옮긴다. 늦게 끝난 옛 커밋이 최신 이미지를 덮어쓰지 않게 하기 위해서다. 다른 브랜치는 수동 실행으로 sha 태그만 만든다.
- EC2는 3분마다 `:dev` 이미지를 확인해 바뀌었을 때만 교체하고, health가 UP이 아니면 직전 이미지로 되돌린다 ([deploy/ec2](../../deploy/ec2/README.md)).

### 실행 환경 구분

| 구분 | Spring 프로필 | compose 프로필 | DB |
|---|---|---|---|
| 로컬 개발 | `dev` (기본값) | `dev`: `mysql` 서비스 | 로컬 Docker MySQL |
| 배포 | `prod` | `prod`: `app` 서비스 | RDS MySQL |
| 부하 테스트 | `perf` | 없음 | 부하 테스트 전용 RDS MySQL |

- `prod` 설정 값은 환경변수로만 주입하고 기본값을 두지 않는다. 값이 빠지면 기동이 실패한다.
- `perf`는 `prod`처럼 환경변수로만 값을 받는다. 다른 점은 가짜 PG를 쓰고 관리 포트(기본 8081)에 `/actuator/prometheus`와 health를 여는 것이다. 가짜 PG가 아니면 기동이 실패한다 (`PerfProfilePgGuard`).
- 앱 컨테이너는 `127.0.0.1:8080`으로만 열고, 외부 요청은 Nginx가 받는다.
- 필수 값 검사는 compose(`${VAR:?}`)가 아니라 `application-prod.yml`이 맡는다. compose는 꺼진 프로필의 서비스까지 변수를 검사하므로, compose에 필수 표기를 쓰면 `dev` 실행도 실패한다.
- 백엔드 `Dockerfile`은 아직 없다. `prod` compose 프로필은 이미지를 만든 뒤에 쓸 수 있다.

## 4. 수평 확장 (EXPERIMENTAL)

단일 서버의 실제 한계를 먼저 측정한다. 확장이 필요하면 다중 EC2, ALB, 공통 입장 제어 상태, 중복 스케줄러 방지, 전체 DB 연결 수, 경매 실시간 전달(SSE)의 서버 간 전달을 검토한다.

확장 목적이 처리량, 가용성, 무중단 배포 중 무엇인지 구분한다.
