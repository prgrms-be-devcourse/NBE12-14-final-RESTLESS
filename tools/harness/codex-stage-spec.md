# Codex 작업 spec 템플릿 (드롭 단계 구현)

전체 작업 순서는 `tools/harness/README.md` 2절이 기준이다. 이 파일은 그중 "지시하기"에 쓰는 템플릿이다.
Claude가 `{...}`를 채워 클립보드에 넣고, 사용자가 Codex 탭(`codex -s danger-full-access -a never`)에 붙여넣는다.

---

## 작업: 드롭 {STAGE} — {TITLE} (이슈 #{ISSUE})

저장소: {REPO}
브랜치: `{BRANCH}` (Claude가 만들어 체크아웃해 두었다. 새 브랜치를 만들지 말 것)
작업 전 `AGENTS.md`를 읽고 따른다.

### 완료 기준 (이것만 통과하면 된다)
1. `backend/src/test/java/com/restless/drop/acceptance/{ACCEPTANCE_CLASS}.java`의 **`@Disabled` 한 줄만 지운다** (필요하면 import 한 줄도). 그 밖의 줄은 바꾸지 않는다.
2. 그 수용 테스트가 전부 통과한다. 이전 단계 수용 테스트와 기존 테스트도 전부 통과한다.
3. `bash tools/harness/harness-check.sh` 결과가 `RESULT: PASS`.

### 하네스 파일 (수정 금지)
- `backend/src/test/java/com/restless/drop/acceptance/**` (위 1번의 `@Disabled` 줄 삭제만 예외)
- `backend/src/test/resources/harness/**`, `backend/src/test/java/com/restless/architecture/**`
- `tools/harness/**` (하네스 도구), `.ai-output/harness/**` (단계 보고서 `reports/{STAGE}-report.md`만 예외)
테스트가 계약과 맞지 않아 보이면 고치지 말고 보고서에 적는다. 테스트를 통과시키려고 동작을 계약과 다르게 만들지 않는다.

### 가드 (구현 중 자동 차단)
아래 행동은 편집 hook과 git pre-commit hook이 막고, 놓친 것은 `harness-check.sh`가 FAIL로 판정한다. 차단되면 우회하지 말고 차단 메시지를 보고서에 그대로 적는다.
- 하네스 파일 수정·추가·삭제 (`@Disabled` 줄과 그 import 삭제만 허용. 이 삭제는 apply_patch로 한다)
- 머지된 마이그레이션 수정 (새 마이그레이션 파일 추가는 허용)
- 다른 도메인(ticketing, fanmeeting, auction, concert, credit, coupon) 코드 수정
- `tools/harness/**`, `.ai-output/harness/**`, `.codex/**`, `.git/hooks/**` 수정, `git commit --no-verify`, `git push`, `HARNESS_EDIT`
- 보호 경로를 셸 명령(sed -i, 리다이렉트, mv, rm, git checkout 등)으로 바꾸는 것. 읽기는 cat/grep/sed -n으로 리다이렉트 없이 한다

### 읽을 것
- 계약: `docs/contracts/api/drop.md` {STAGE} 절 (요청·응답·오류 코드의 기준)
- 정책·불변식: `docs/domains/drop/policy.md`, `invariants.md`, `state-machines.md`, `transactions.md`, `data-model.md`
- 사용자가 정한 것: `.ai-output/harness/drop-decisions.md`
- 공통 모듈 사용법: `order/application/OrderService` (쓰기는 호출자 트랜잭션 필수), `payment/application/payable/*` (Handler 계약)
{EXTRA_READING}

### 범위
{SCOPE}

### 규칙
- 다른 도메인의 Repository를 쓰지 않는다. 주문은 `OrderService`, 결제는 `PaymentResultHandler` 계약으로만 (ArchUnit이 검사).
- 새로 만든 빈 때문에 다른 모듈의 기존 테스트 **구성**(가짜 빈 등록 등)이 충돌하면, 그 테스트 구성만 고치는 것은 허용한다. 테스트 본문·단언과 그 모듈의 운영 코드는 바꾸지 않고, 고친 이유를 보고서에 적는다 (HD-12). 멈춰서 묻지 않는다.
- 다른 도메인 패키지를 고치지 않는다. 공용 파일(`ErrorCode`, `SecurityConfig`, `application.yml`, `build.gradle`)은 꼭 필요한 최소 변경만 하고 보고서에 이유를 적는다. `ErrorCode`는 맨 끝에 추가.
- 머지된 마이그레이션은 고치지 않는다. 스키마 변경은 새 마이그레이션(`V{YYYYMMDD_HHMM}__drop_*.sql`).
- 핵심 상태는 트랜잭션 안에서 커밋. PG 호출 중 트랜잭션 금지. Outbox·Kafka 금지.
- 메트릭 태그에 ID 같은 끝없이 늘어나는 값을 넣지 않는다. 로그에 시크릿·paymentKey를 남기지 않는다.
- 시각은 주입된 `Clock`. 테스트에서 고정 시계로 JWT를 발급하지 않는다.
- 정책이 문서에 없으면 멈추지 말고 문서에 가장 가까운 쪽으로 정해 보고서의 "대신 정한 것"에 적는다.
- 필요 이상의 추상화를 만들지 않는다.
- 기본값을 바꾸는 설정(예: `restless.drop.queue.required`)을 건드리면, 그 설정에 기대는 기존 테스트를 찾아 필요한 테스트에 값을 명시한다. 다른 단계가 동시에 진행 중이면 보고서에 "다른 단계에 영향 줄 수 있는 기본값 변경"으로 적는다.
- 가드는 보호 경로에 쓰는 명령을 막는다. 로그는 `> .ai-output/<이름>.log`처럼 보호 경로 밖으로 쓴다. 보호 경로를 `cp`·`python`으로 다루지 않는다.

### 마무리
1. `bash tools/harness/harness-check.sh` → `RESULT: PASS`가 나올 때까지 고친다.
   - 검사는 브랜치가 최신 `origin/dev`보다 뒤처져 있으면 **dev를 합친 결과**를 빌드한다 (CI와 같음). `최신 origin/dev와 충돌함`이 나오면 `git merge origin/dev`로 합쳐 충돌을 정리하고 그 병합 커밋도 남긴다. 기존 테스트가 합친 결과에서만 깨지면 그것도 이번 작업에서 고친다.
2. **커밋을 작은 논리 단위로 나눈다** (PR 하나 = 커밋 하나로 만들지 않는다). 바꾼 파일만 경로로 지정해 아래 순서처럼 나눠 커밋한다. **push, PR 금지.**
   - `feat(drop): add <...> migration` — 새 마이그레이션
   - `feat(drop): map <...> entities and repositories` — 엔티티·리포지토리
   - `feat(drop): add <...> service` — application 서비스·DTO
   - `feat(drop): expose <...> API` — 컨트롤러 (+ `ErrorCode`처럼 공용 파일은 따로 `feat(common): ...`)
   - `test(drop): cover <...>` — 단위·통합 테스트
   - `test(drop): enable <단계> acceptance tests` — 수용 테스트 `@Disabled` 삭제만
   각 커밋은 컴파일되어야 한다. 커밋 메시지는 Conventional Commits, 영어 명령형, 본문 끝에 `Refs #{ISSUE}`.
3. 보고서 `.ai-output/harness/reports/{STAGE}-report.md`: 바뀐 파일, harness-check 마지막 출력, 설계 판단, 대신 정한 것, 계약과 다른 점, 남은 위험.
4. 마지막으로 탭에 `D? 완료`와 보고서 경로를 출력하고 멈춘다. (보고서 파일과 커밋으로 완료를 감지한다. 보고서는 커밋까지 끝낸 뒤 마지막에 쓴다.)
