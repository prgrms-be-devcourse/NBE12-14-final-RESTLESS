# 드롭 하네스

AI가 드롭 기능을 구현하고(Codex) 다른 AI가 검증할 때(Claude) 쓰는 작업 환경입니다. 이 문서가 작업 순서의 **유일한 기준**입니다. 다른 메모나 진행표에 적힌 순서가 이 문서와 다르면 이 문서를 따릅니다.

핵심 생각은 하나입니다. **완료 기준(수용 테스트)을 구현보다 먼저 만들고, 구현하는 쪽은 그 기준을 고칠 수 없게 합니다.**

## 1. 어디에 무엇이 있나

| 무엇 | 위치 | 성격 |
|---|---|---|
| 무엇이 맞는가 (계약, 불변식, 정책, 테스트 전략) | `docs/contracts/api/drop.md`, `docs/domains/drop/`, `docs/testing/strategy.md` | 사람이 합의하는 기준 |
| 자동 채점 (수용 테스트, 불변식 SQL, 모듈 경계) | `backend/src/test/java/com/restless/drop/acceptance/`, `backend/src/test/resources/harness/`, `backend/src/test/java/com/restless/architecture/` | CI가 실행. 구현 단계에서는 `@Disabled` 줄 삭제만 허용 |
| 작업 도구 (이 폴더) | `tools/harness/` | 검사 스크립트, 가드, 지시서 템플릿, 테스트 힘 검사 |
| 실행 기록 | `.ai-output/harness/` (git-ignore, 개인 PC) | 진행표, 단계 보고서, 차단 기록, 빌드 로그, 대신 정한 정책 |

`tools/harness/` 파일:

| 파일 | 하는 일 |
|---|---|
| `harness-check.sh` | 최종 판정. 규칙 검사 + 최신 dev와 합친 결과로 전체 빌드·테스트 |
| `guard/guard.py` | 구현 중 조기 차단 (Codex 편집 hook, git pre-commit) |
| `guard/test_guard.py` | 가드 자체 테스트 |
| `test-strength.py` | 수용 테스트가 실제로 실패할 수 있는지 검사 (red, mutants) |
| `mutants/drop.json` | 일부러 넣어 볼 버그 목록 |
| `codex-stage-spec.md` | Codex에게 줄 단계 지시서 템플릿 |
| `install.sh` | 가드 hook 설치 (선택, 개인 PC에서만) |

## 2. 작업 순서 (한 단계)

1. **기준 만들기** (Claude, 하네스 브랜치 `chore/drop-<이슈>-harness`)
   - 계약 절을 쓰고, 수용 테스트를 `@Disabled` 상태로 추가한다.
   - `python3 tools/harness/test-strength.py red <수용 테스트 클래스>` → `RESULT: PASS`. 구현 없이 통과하는 테스트가 있으면 아무것도 검사하지 않는 테스트이므로 고친다.
   - 하네스 PR로 올린다. 이 PR만 `HARNESS_EDIT=1`로 검사한다.
2. **지시하기** (Claude → 사람)
   - 이슈와 단계 브랜치를 만들고, `codex-stage-spec.md`의 `{...}`를 채워 클립보드에 넣는다.
   - 사람이 Codex 탭에 붙여넣는다. 자동 작업자(Orca)는 안전장치에 막혀 쓰지 않는다.
3. **구현** (Codex)
   - `@Disabled` 한 줄만 지우고 구현한다. `bash tools/harness/harness-check.sh`가 `RESULT: PASS`가 될 때까지 고친다.
   - 작은 단위로 커밋하고(push 금지) `.ai-output/harness/reports/<단계>-report.md`를 마지막에 쓴다.
4. **검증** (Claude)
   - 보고서가 생기면 검사를 다시 돌린다. 이때 단계 브랜치에 들어 있는 사본이 아니라 **dev 쪽 `tools/harness/` 사본으로** 돌린다.
   - 코드를 리뷰하고, 문제가 있으면 수정 지시서를 보낸다 (최대 3번). 통과하면 push하고 PR을 만든다.
5. **머지** (사람)
   - 머지 뒤 `python3 tools/harness/test-strength.py mutants`로 수용 테스트가 알려진 버그를 잡는지 확인한다. 기준점이 사라진 버그(STALE)는 `mutants/drop.json`을 갱신한다.
6. **기록** (Claude)
   - `.ai-output/harness/progress.md`에 평가 한 줄을 남긴다 (아래 5절).
   - 정책을 대신 정했으면 `.ai-output/harness/decisions-to-review.md`에 남긴다. 멈추지 않고 문서에 가장 가까운 쪽으로 정하고, 나중에 사람이 검토한다.

## 3. 검사는 세 겹이다

| 겹 | 언제 | 정확도 |
|---|---|---|
| ① Codex 편집 hook (`guard.py codex-hook`) | 명령을 실행하기 직전 | 파일 패치는 정확하게 본다. 셸 명령은 경로 글자를 찾는 방식이라 돌려 쓴 명령(예: `cd` 뒤 상대 경로)은 놓친다. 조기 경고 역할 |
| ② git pre-commit (`guard.py pre-commit`) | 커밋 직전 | 실제로 바뀐 파일을 본다 |
| ③ `harness-check.sh` | 단계를 끝낼 때 | 기준 커밋과의 diff + 전체 빌드. **최종 판정** |

막는 것은 다음과 같습니다.
- 하네스 파일 수정 (`@Disabled` 줄과 그 import 삭제만 허용)
- dev에 머지된 마이그레이션 수정
- 다른 도메인(ticketing, fanmeeting, auction, concert, credit, coupon) 코드 수정. 브랜치 이름과 관계없이 적용
- `tools/harness/`, `.ai-output/harness/`, `.codex/`, `.git/hooks/`, `.claude/` 수정
- `git commit --no-verify`, `git push`, `HARNESS_EDIT`, hook 경로 변경

## 4. 명령

모두 저장소 루트(또는 단계 worktree)에서 실행합니다.

| 목적 | 명령 |
|---|---|
| 최종 판정 | `bash tools/harness/harness-check.sh` |
| 규칙만 빠르게 (판정용 아님) | `SKIP_BUILD=1 bash tools/harness/harness-check.sh` |
| 하네스 PR 검사 | `HARNESS_EDIT=1 bash tools/harness/harness-check.sh` |
| 구현 전 실패 확인 | `python3 tools/harness/test-strength.py red DropXxxAcceptanceTest [--ref <커밋>]` |
| 넣은 버그를 잡는지 | `python3 tools/harness/test-strength.py mutants [--only M1,M2]` |
| 가드 테스트 | `python3 tools/harness/guard/test_guard.py <worktree>` (드롭·하네스·일반 브랜치에서 각각) |
| 가드 설치 | `bash tools/harness/install.sh` |

`harness-check.sh`는 브랜치가 최신 `origin/dev`보다 뒤처져 있으면 임시 폴더에서 "브랜치 + dev"를 합쳐 빌드합니다. CI와 같은 방식입니다. 충돌이 나면 빌드하지 않고 FAIL을 냅니다.

`test-strength.py`는 임시 git worktree에서 돌기 때문에 작업 폴더를 건드리지 않습니다. Docker가 필요하고, 버그 하나마다 수용 테스트 클래스 하나를 돌립니다. 결과는 다음 넷 중 하나입니다.
- `CAUGHT`: 지정한 테스트가 잡음
- `CAUGHT-ELSEWHERE`: 다른 테스트가 잡음
- `SURVIVED`: 아무도 못 잡음. 테스트를 보강하거나, 방어선이 여러 겹이라 한 곳만 망가뜨려서는 드러나지 않는 경우라면 버그를 다시 정의한다
- `STALE`: 망가뜨릴 코드가 사라짐

## 5. 하네스 평가

코드가 기준을 통과했는지가 아니라 **하네스가 일을 잘 시키는지**를 잽니다. 단계가 끝날 때 `.ai-output/harness/progress.md`에 한 줄씩 남깁니다. 모르는 값은 추측하지 않고 `?`로 둡니다.

| 지표 | 정의 | 좋은 방향 |
|---|---|---|
| 첫 검사 | Codex의 첫 `harness-check` 결과. FAIL이면 원인을 구현/하네스로 나눔 | PASS |
| 수정 요청 | Claude가 보낸 수정 지시 수 (상한 3) | 0 |
| 하네스 결함 | 하네스(스크립트·테스트·계약·지시서) 탓의 오판·장애 수 | 0 |
| 가드 차단 | 정당한 차단과 오판을 나눠 셈 (`guard-blocks.log`) | 오판 0 |
| 대신 정한 정책 | `decisions-to-review.md`에 추가된 행 수 | 적을수록 계약이 충분함 |
| 계약 변경 | 구현 중 계약·수용 테스트를 고친 수 | 0 |
| 사람 개입 | 사람이 루프를 멈추고 직접 조치한 횟수 | 0 |
| 소요 | 지시 붙여넣기 → Claude 검증 PASS (분). 질문 대기는 따로 적음 | 짧게 |
| 리뷰 후 결함 | 머지 뒤 발견된, 하네스가 잡았어야 할 결함 | 0 |
| 버그 검출 | `test-strength.py mutants`의 잡은 수/전체 | 전부 |

하네스 결함은 `HD-<번호>`로 기록합니다. 단계, 결함, 영향, 조치, **고친 커밋**을 남깁니다. 번호는 다시 쓰지 않습니다.

## 6. 알려진 한계

- 아직 dev에 없는 두 단계 브랜치끼리의 조합(예: D3+D4)은 검사가 보지 못한다. 병렬로 진행하면 먼저 머지된 쪽을 기준으로 나머지를 다시 검사한다.
- 셸 명령 검사(①)는 패턴 검사라 우회될 수 있다. 판정은 ②와 ③이 한다.
- 버그 목록(`mutants/drop.json`)은 사람이 고른 8개뿐이다. 전부 잡는다고 테스트가 완전하다는 뜻은 아니다.
- 서버 여러 대 상황과 대량 트래픽은 수용 테스트 범위 밖이다. 트래픽 테스트(D5)에서 다룬다.
- 드롭 전용 설정(보호 경로, 다른 도메인 목록)은 `guard.py` 맨 위, `harness-check.sh`, `test-strength.py`에 있다. 다른 도메인이 이 하네스를 쓰려면 그 부분을 바꾼다.

## 7. 하네스를 바꾸려면

1. `chore/drop-<이슈>-harness` 브랜치를 만든다. 이 브랜치에서만 `tools/harness/`와 하네스 테스트를 커밋할 수 있다. Codex 편집 hook은 어느 브랜치에서도 이 폴더를 막는다.
2. 가드를 바꿨으면 `test_guard.py`를 드롭·하네스·일반 브랜치에서 각각 돌린다.
3. `HARNESS_EDIT=1 bash tools/harness/harness-check.sh`로 검사하고 PR을 연다.
4. 아직 머지되지 않은 하네스 브랜치 위에 단계를 쌓으면, `harness-check.sh`가 그 하네스 커밋을 기준으로 삼는다 (`HARNESS_BRANCH` 환경 변수로 브랜치 이름을 바꿀 수 있다).
