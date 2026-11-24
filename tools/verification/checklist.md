# 검증 체크리스트

독립 검증의 규칙 정본이다. [SKILL.md](skills/independent-verification/SKILL.md)가 단계마다 이 파일의 절을 가리킨다.
항목은 실제로 잡은 결함에서만 추가하고 결함 번호를 붙인다. 결함 원장은 `.ai-output/harness-eval/defects.tsv`(개인 파일)다.

## 1. 사전 반례: 변경 유형별

precheck에 해당 유형의 반례를 모두 적는다. 해당 없으면 이유를 쓴다.

| 변경 유형 | 반례 | 결함 |
|---|---|---|
| 인터셉터, 필터, 경로 매칭 규칙 | 같은 핸들러에 닿는 메서드 변형(GET과 HEAD, OPTIONS), 끝 슬래시, 대소문자, 이중 슬래시, 인코딩. 규칙이 문자열 비교인지 MVC 매칭 결과인지 확인 | L1 |
| 정렬 기준, 배치 한도, 실패 재시도가 함께 있는 후보 조회 | 배치 크기 이상의 영구 실패 건 뒤에 정상 건 1개. 두 번째 실행에서 정상 건이 처리되는지. 정렬 열이 실패 경로에서 갱신되는지, 그 갱신이 실패 트랜잭션과 함께 롤백되는지 | L3 |
| 시간 창, 만료, 주기 | 첫 창 초과, 창 경과 후 통과, 두 번째 창에서 다시 초과. 경계 시각 정확히 | L2 |
| 여러 호출 경로가 쓰는 메서드 수정 | 호출 경로를 모두 나열하고 각 경로의 동작 변화를 적는다 | #275 검증 |

## 2. 테스트 강도

- 새 테스트는 기준 SHA에서 단언 실패로 red가 되는지 확인한다. 컴파일 오류나 준비 오류는 red가 아니다.
- 아래 경로를 바꾼 변경은 변이 시험이 필수다. review.md에 변이 결과를 적는다.
  - 미완료 결제 재확인 작업: `payment/application/PaymentReconciler.java`, `payment/application/PaymentTransactions.java`
  - 결제 상태 전이: `payment/domain/**`
  - 재고 점유와 정산: `drop/**` 중 이름에 `Inventory`나 `Reservation`이 들어간 파일
- 변이는 하네스 목록(`tools/harness/mutants/*.json`, 개인 파일)이 있으면 `test-strength.py mutants --file <목록>`으로 실행한다. 목록에 없는 변이를 손으로 넣었으면 파일과 원문 문자열을 기록하고 목록 추가를 제안한다. 적용에 실패한 변이는 잡힘과 생존이 아니라 미적용으로 따로 센다 (L8).
- 알려진 변이를 모두 잡았다는 것과 검증이 완전하다는 것은 다르다. 경로 우회 같은 반례는 별도 probe로 확인한다 (L1).

## 3. 실행 증거

- 전체 build는 같은 대상 SHA의 CI `backend` 작업 결과를 증거로 쓸 수 있다. 조건은 넷이다.
  1. CI 실행의 PR head SHA가 검증 SHA와 같다. PR CI는 그 시점 base와 합친 병합본을 build하므로, 검증 SHA 자체보다 머지 결과에 가깝다.
  2. 변경에 `.github/workflows/`, `backend/build.gradle`, `backend/settings.gradle`, `backend/gradle/`, `backend/gradlew`, `frontend/package.json` 수정이 없다.
  3. CI 테스트 결과 artifact(`backend-test-results`)에서 테스트 수와 실패 0을 확인해 review.md에 적는다. artifact는 7일 보관이라 그 뒤에는 직접 build한다.
  4. CI가 전체 테스트를 돌렸다. 드롭, 주문, 결제 코드가 바뀐 PR은 CI가 항상 전체를 돌린다. 그 밖의 PR과 dev push CI는 영향받는 테스트만 돌릴 수 있다 (`tools/ci/select-backend-tests.py`). artifact의 `test-scope.txt` 첫 줄이 `scope: full`이어야 한다. 전체 기준 테스트 수는 2026-11-24에 1195개였다. `merge-gate.py`가 그 실행의 `Backend test scope` 안내를 읽어 알려 준다. 안내가 없으면 CI 증거로 쓰지 않는다.
- 네 조건을 모두 만족하면 검증자는 전체 build를 다시 돌리지 않는다. 하나라도 어긋나면 검증자가 직접 `./gradlew build`를 실행한다. 대상 테스트와 변이 시험은 항상 직접 실행한다.
- 로그와 임시 probe 결과는 메인 폴더의 `.ai-output/verification/<issue>/`에 절대 경로로 쓴다. worktree 안의 `.ai-output`은 worktree 삭제 때 사라진다 (L6).

## 4. 증분 재검증

FAIL 뒤 수정을 같은 검증자 계열에게 변경분만 다시 보게 할 수 있다. 아래를 모두 만족할 때만 그렇게 하고, 하나라도 어긋나면 새 검증자가 처음부터 한다.
- 수정이 새 커밋으로만 쌓였다 (amend와 force push 없음).
- diff가 FAIL 사유와 그 테스트 범위 안이다.
- 이전 FAIL 기록을 지우지 않고 재검증 절을 덧붙인다.
- 이전 생존 변이와 수정 대상 변이를 다시 실행한다.
- 전체 build 증거를 새 SHA로 다시 확보한다 (3절).

## 5. 위험도와 머지 전 대조

머지 직전에 `python3 tools/verification/bin/merge-gate.py <PR>`을 실행한다. 위험도는 gate가 diff로 자동 분류하고 근거 줄을 출력한다. 위험도만 보려면 `--classify-only`를 붙인다. 판단이 애매하면 높음이다.

| 위험도 | 해당하는 변경 | 요구 |
|---|---|---|
| 높음 | 아래 표의 경로나 diff 내용 | head SHA의 새 컨텍스트 독립 PASS를 머지 전에 받는다. 변이 시험은 2절의 핵심 경로에서 바뀐 핵심 조건에만 한다 |
| 중간 | 높음도 낮음도 아닌 변경. 운영 코드, 설정 기본값, perf 스크립트 | 작성자 자기 검증, CI, 하네스. 독립 검증 대신 diff 확인으로 충분하다. 운영 배포 전이면 머지 뒤 검증도 허용한다 |
| 낮음 | 문서만, 테스트 추가만, 높음이 아닌 작은 후속 커밋 | CI와 하네스 통과로 충분하다 |

높음으로 보는 경로. 이 목록은 merge-gate.py의 `HIGH_PATHS`와 같게 유지한다.

| 이유 | 경로 |
|---|---|
| Flyway 마이그레이션 | `backend/src/main/resources/db/migration/*` |
| 공개 API 계약 | `docs/contracts/*` |
| 채점 테스트 | `backend/src/test/java/com/restless/drop/acceptance/*`, `backend/src/test/java/com/restless/architecture/*`, `backend/src/test/resources/harness/*`, `tools/harness/*` |
| 불변식 핵심 코드 | 2절의 변이 시험 필수 경로 |
| 불변식, 트랜잭션, 상태 전이 문서 | `docs/domains/*/invariants.md`, `transactions.md`, `state-machines.md`, `idempotency.md`, `seat-ownership.md`, `docs/operations/failure-handling.md` |
| 검증과 CI 도구 자체 | `tools/verification/*`, `tools/ci/*`, `.github/*` |
| 빌드 설정 | `backend/build.gradle`, `backend/settings.gradle`, `backend/gradle/*`, `backend/gradlew`, `frontend/package.json` |

경로와 별개로 diff 내용이 아래와 같으면 높음이다.
- 에이전트 지침 파일이 바뀜. `AGENTS.md`, `CLAUDE.md`, `SKILL.md`.
- 운영 코드나 SQL에 `@Transactional`, `TransactionTemplate`, `FOR UPDATE`, `SKIP LOCKED`, `@Lock`, `@Modifying`, `@Version`, `synchronized`가 새로 생김. 주석 줄은 세지 않는다.
- 테스트 파일에서 단언이나 `@Test` 줄이 지워지거나 바뀜, 테스트 파일 삭제, `@Disabled`나 건너뛰기 추가.
- diff를 읽지 못했거나 변경 파일 목록이 비어 있음.

위 어느 것도 아니면 낮음 조건을 본다. 낮음은 모든 파일이 문서이거나 모두 테스트 파일이고 높음 근거가 없을 때다. 문서는 `docs/` 아래와 저장소 루트의 `.md`뿐이다. `perf/`와 `tools/` 아래 `.md`는 문서가 아니다. 나머지는 중간이다.

gate는 참고로 변경 종류도 출력한다. 코드, 정책 문서, 코드 설명 문서(`docs/operations/**`, `docs/architecture/**`), 서식 문서다. 요구를 정하는 것은 위험도다.

사람은 PR 라벨 `risk:high`, `risk:medium`이나 본문 한 줄 `위험도: 높음`, `위험도: 중간`으로 단계를 올릴 수 있다. 낮추는 표시는 무시하고 그 사실을 출력한다.

작은 후속 커밋. 높음 PR에서 독립 PASS를 받은 뒤 쌓인 커밋은 아래를 모두 만족하면 다시 독립 검증하지 않는다. 합쳐서 30줄 이하이고, 그 변경만 분류해도 높음이 아니고, compare 결과의 patch를 모두 읽을 수 있어야 한다. 하나라도 어긋나면 4절이나 새 검증을 따른다.

위험도와 상관없이 정책 문서(`docs/domains/**`, `docs/contracts/**`, `docs/decisions/**`, `docs/product/**`, `docs/testing/**`) 변경은 사람 확인이 필요하다는 안내가 나온다. 결제 핵심 경로를 바꾼 높음 PR은 review.md에 변이 결과가 없으면 막힌다. 판정 기록은 review.md의 마지막 비어 있지 않은 줄만 읽는다. 모든 위험도에서 backend CI가 실패하면 막힌다.

SHA가 다르면 재검증하거나, 두 트리의 차이를 확인해 빠진 파일을 review.md에 적는다. 2026-11-20에 #274는 프로덕션 코드가 같았지만 검증받은 테스트 4개가 빠진 채 머지됐다.

## 6. Codex 지시서

지시서는 [codex-task-spec.md](codex-task-spec.md)로 쓰고, 넘기기 전에 `python3 tools/verification/bin/spec-lint.py <지시서>`를 실행한다.
- 이슈 번호를 하나로 고정한다. 다음 작업은 새 지시서로 넘긴다 (L4).
- 작업 폴더가 worktree이면 그 폴더의 `git ls-files`에 있는 도구만 실행하게 한다 (L5).
- 보고서와 로그 등 `.ai-output` 경로는 메인 폴더 절대 경로로 쓴다 (L6).

## 7. 검증 예산, 불안정한 테스트, 기준 조정

검증 예산. 독립 검증 하나의 목표 상한은 30분과 토큰 10만 안팎이다. 넘기면 그때까지 찾은 것으로 `INCOMPLETE`와 남은 범위를 review.md에 적고 끝낸다. 더 볼지는 사람이 정한다. 예산이 모자라다는 이유로 PASS를 주지 않는다.

불안정한 테스트. 검증 중 이 변경과 무관해 보이는 테스트가 실패하면 같은 명령을 한 번 다시 돌린다. 통과하면 재실행으로 분류하고 이슈를 등록한 뒤 넘어간다. review.md에 테스트 이름, 실패 메시지, 이슈 번호를 적는다. 다시 돌려도 실패하거나 이 변경이 닿는 테스트면 불안정으로 보지 않고 FAIL로 다룬다.

기준 조정. 원장 `.ai-output/harness-eval/ledger.tsv`에 `tier` 열을 두고 gate가 출력한 위험도를 적는다. 열이 없으면 첫 줄에 추가한다. 결함은 `defects.tsv`에 남긴다. 주마다 단계별로 독립 검증이 실제 결함을 잡은 비율을 본다. 중간이나 낮음에서 머지 뒤 결함이 나오면 그 변경의 근거를 찾아 diff 규칙을 높음으로 옮긴다. 높음에서 결함이 한 번도 안 나온 규칙은 한 단계 낮출지 사람이 정한다.

