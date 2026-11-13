# NBE12-14-final-RESTLESS

팀 **쉬고 싶지 않음 청년**의 데브코스 최종 프로젝트. 아티스트와 팬을 연결하는 팬덤 이벤트 커머스 플랫폼이다.

동시 요청이 몰리는 상황에서 거래 정합성을 지키고, 처리량과 지연시간을 측정하며 개선하는 것이 기술 목표다.

## 도메인

| 도메인 | 담당 | 핵심 |
|---|---|---|
| 한정판 드롭 | 이태호 | 구매 대기열, 재고 예약, PG 결제 |
| 티켓팅 | 송혜민 | 지정 좌석 선점, 결제 |
| 경매 | 천종원 | 실시간 입찰, 크레딧 |
| 팬미팅 추첨 | 홍승연 | 응모, 추첨, 당첨자 결제 |

## 구조

```text
.
├── AGENTS.md        # AI 코딩 에이전트 공통 규칙 (Codex, Claude Code)
├── CLAUDE.md        # Claude Code용, AGENTS.md를 불러옴
├── CONTRIBUTING.md  # 브랜치, PR, 커밋 규칙, 로컬 실행
├── backend/         # Java 21, Spring Boot 4.1, Gradle, MySQL 8
├── frontend/        # React, Vite, TypeScript
├── docs/            # 설계 문서. 시작은 docs/README.md
└── tools/harness/   # AI 구현·검증 하네스 도구 (드롭). 설명은 tools/harness/README.md
```

## 기술 스택

Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, MySQL 8, Testcontainers, React, Vite, TypeScript, 토스페이먼츠(테스트), k6

## 문서

[docs/README.md](docs/README.md)에서 시작한다. 통합 ERD는 [docs/architecture/erd.md](docs/architecture/erd.md), API는 [docs/contracts/api/](docs/contracts/api/README.md)에 있다.
