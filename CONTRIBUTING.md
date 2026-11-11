# 협업 규칙

## 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 배포 기준. 직접 push 금지 |
| `develop` | 통합 브랜치. 모든 작업 PR은 여기로 |
| `feat/<도메인>-<이슈번호>-<요약>` | 기능 (예: `feat/drop-12-inventory-reserve`) |
| `fix/<도메인>-<이슈번호>-<요약>` | 버그 수정 |
| `docs/<요약>` | 문서 |
| `chore/<요약>` | 빌드, 설정 |

도메인: `drop`, `ticketing`, `coupon`, `auction`, `common`, `infra`, `fe`

## PR

1. `develop`을 대상으로 연다.
2. 승인 2명 이상, CI 통과 후 머지한다.
3. 머지 방식은 Squash merge로 통일한다.
4. PR 템플릿의 항목(변경 이유, 관련 불변식, 실제로 실행한 테스트)을 채운다.
5. 다른 도메인의 코드나 공통 계약(API, ERD)을 바꾸면 해당 담당자를 리뷰어로 넣는다.

`develop` → `main`은 배포 시점에 맞춰 PR로 올린다.

## 커밋 메시지

Conventional Commits, 영어: `type(scope): description`

예: `feat(drop): reserve inventory with conditional update`

type: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`

## 로컬 실행

```bash
# 백엔드
cd backend
cp .env.example .env.local   # 값 채우기
docker compose up -d         # MySQL
./gradlew bootRun

# 프론트엔드
cd frontend
cp .env.example .env.local
npm install
npm run dev                  # /api 요청은 localhost:8080으로 프록시
```

`.env.local`은 커밋하지 않는다. 토스 테스트 키 같은 값은 팀 채널로 공유한다.

## DB 마이그레이션 (Flyway)

스키마는 Flyway SQL 파일로만 바꾼다. `ddl-auto`는 `validate`라서 엔티티와 테이블이 다르면 기동이 실패한다. 엔티티를 추가하거나 바꾸면 같은 PR에 마이그레이션 파일을 넣는다.

- 위치: `backend/src/main/resources/db/migration/`
- 파일 이름: `V<날짜>_<시각>__<도메인>_<내용>.sql` (예: `V20261111_1030__drop_create_tables.sql`). 순번(V1, V2)은 여러 명이 같은 번호를 만들어 충돌하므로 쓰지 않는다.
- 브랜치 머지 순서가 파일 시각과 다를 수 있으므로 `out-of-order`를 켜 두었다.
- `develop`에 머지된 마이그레이션은 고치지 않는다. 바꿀 내용은 새 파일로 추가한다. 이미 적용된 파일을 고치면 체크섬 오류로 모두의 기동이 멈춘다.
- 첫 배포 전까지는 로컬 DB를 초기화해도 된다: `backend`에서 `docker compose down -v` 후 `docker compose up -d`.
- 제약 조건(CHECK, UNIQUE, 생성 컬럼)은 JPA 어노테이션이 아니라 마이그레이션 SQL에 쓴다. 정합성을 DB가 마지막으로 지키는 장치다.

## 환경변수

- 어떤 변수가 있는지는 `backend/.env.example`, `frontend/.env.example`이 정본이다.
- 새 환경변수를 코드에 추가하는 PR은 같은 PR에서 `.env.example`에 설명과 함께 추가한다.
- `.env.example`에는 로컬 전용 더미 값만 쓴다. 실제 키는 넣지 않는다.
- 배포 환경에서는 `.env` 파일이 아니라 환경변수나 GitHub Secrets로 주입한다.

## AI 결과물

Claude, Codex, ChatGPT가 만든 계획, 보고서, 분석 메모는 `.ai-output/`에 둔다. 이 폴더는 커밋되지 않는다. 팀이 함께 볼 내용이면 `docs/`로 옮겨 PR로 올린다.
