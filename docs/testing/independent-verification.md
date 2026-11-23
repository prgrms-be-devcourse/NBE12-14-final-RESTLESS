# AI 독립 검증 절차

구현 완료 검토와 PR 승인 준비 시 [전용 스킬](../../tools/verification/skills/independent-verification/SKILL.md)을 **직접 읽고 실행한다**. 개인 전역 스킬 설치는 필요 없다. 기존 `code-review`를 사용해도 이 스킬의 사전 반례와 Assumptions 검토를 먼저/함께 수행한다. 테스트 실행·하네스 판정은 기존 절차를 대체하지 않는다.

## 사용하기

새 세션에 다음처럼 요구사항과 정확한 ref/SHA를 전달한다. 구현 보고서는 사전 반례 기록 뒤 전달한다.

```text
이슈 #N의 요구사항과 저장소 지침으로 검증하라.
기준: <base SHA>, 대상: <head SHA>.
tools/verification/skills/independent-verification/SKILL.md를 읽고 따른다.
먼저 precheck.md를 작성하라. 이후 diff와 구현 보고서를 대조하고 직접 검증하라.
review.md 마지막에 판정 기록 줄을 남겨라.
```

이 파일과 스킬이 저장소의 배포본이다. 새 checkout에서도 위 상대 경로를 읽는다. 드롭 AGENTS.md와 문서 색인에서 연결한다. 루트 AGENTS.md/CLAUDE.md와 하네스는 개인 파일이므로 다른 PC에서는 아래 개인 설정을 별도로 적용한다.

## 결함을 규칙으로 쌓기

검증에서 잡은 결함은 [체크리스트](../../tools/verification/checklist.md)에 결함 번호와 함께 규칙으로 추가한다. 반례, 변이 시험, 실행 증거, 재검증, 머지 전 대조, 지시서 규칙의 정본이 그 파일이다.
2026-11-20 평가에서 사람이 정한 것. 세부 조건은 체크리스트가 정본이다.
- 문서만 바꾼 PR도 경로에 따라 검증한다 (5절).
- 같은 SHA의 CI 결과를 조건부로 전체 build 증거로 쓴다. 같은 build를 두 번 돌리지 않기 위해서다 (3절).
- 결제 핵심 경로를 바꾸면 변이 시험이 필수다 (2절).
- FAIL 뒤 수정은 조건을 만족하면 같은 검증자 계열이 변경분만 재검증한다 (4절).

검증 1회의 결과는 메인 폴더의 개인 원장 `.ai-output/harness-eval/ledger.tsv`에 한 줄로, 결함은 같은 폴더의 `defects.tsv`에 남긴다. 열 정의는 각 파일의 첫 줄이다.

## 정책 결정 범위 (이번 PR의 절차 제안)

기존 루트 지침의 공개 계약·핵심 불변식 보호와 열린 질문의 사람 결정 원칙을 유지한다. 드롭 지침의 “문서에 없는 정책은 정해 진행”은 아래 구현 세부사항으로 한정하도록 제안한다. 사업 정책을 이 문서에서 확정하지 않는다.

| 구분 | 처리 |
|---|---|
| 관찰 가능한 동작·계약·불변식을 바꾸지 않는 내부 구현 선택 | 진행하고 근거를 기록 |
| 기존 합의 문서끼리 충돌하거나 요구사항 누락이 외부 동작을 바꿈 | 쟁점과 대안을 기록하고 영향 없는 작업만 진행; 영향받는 동작은 결정 전 확정하지 않음 |
| 금액·구매 자격·재고 소유·결제 UNKNOWN·환불·공개 계약·핵심 불변식·open-questions | 사람의 결정 필요. 기존 합의된 안전 규칙 유지. PROPOSED 표기는 승인 아님 |

## 개인 하네스와 권한 설정

사용자 승인으로 Codex와 Claude 모두 일반 commit·push·PR을 수행할 수 있다. 하네스·검증 도구와 에이전트 지침의 패치 편집은 `chore/drop-<issue>-harness` 브랜치에서 허용한다. 다른 브랜치의 수용 테스트·검증 스킬 보호, 머지된 마이그레이션 보호, hook 우회 금지는 유지한다. `HARNESS_EDIT`도 하네스 브랜치에서만 허용한다. PR 생성 권한은 독립 검증 완료를 뜻하지 않는다.

현재 PC의 개인 하네스·루트 지침에는 반영했다. [integration.patch](../../tools/verification/integration.patch)는 Git 제외된 개인 하네스 변경의 배포본이다. 같은 이전 버전의 사본을 가진 다른 PC에서 `git apply --check tools/verification/integration.patch`로 확인 후 적용한다. 이미 적용된 PC에서는 `git apply --reverse --check tools/verification/integration.patch`로 대조한다. 하네스가 없는 새 checkout에는 이 패치를 적용할 수 없다. Git에 포함된 드롭 지침은 패치 대상에서 제외해 이중 적용을 피한다. 개인 루트 AGENTS.md에는 다음 포인터를 추가한다.

> 구현 검증·PR 승인 준비 전에 `tools/verification/skills/independent-verification/SKILL.md`를 읽고 따른다. 문서에 없는 정책의 결정 범위는 `docs/testing/independent-verification.md`를 따른다. Codex와 Claude 모두 commit·push·PR을 수행할 수 있다. 하네스·검증 도구와 지침 편집은 하네스 브랜치에서 한다.

검증 경로는 가드의 패치·셸 검사와 최종 하네스 diff 검사에 추가했다. 가드는 브랜치 이름으로 편집 범위를 구분한다. 별도의 OS 권한 분리나 사람이 승인한 보호 브랜치를 대체하지 않으며, 사용자가 읽으라고 지시하지 않은 새 환경에서 스킬 자동 호출을 보장하지 않는다.

## 사람이 PR에서 결정할 것

1. 위 구현 선택/사업 정책 경계를 채택할지.
2. 동일 구현자의 예외는 새 컨텍스트 검증자 또는 사람 검토를 추가해야 완료로 볼지.
3. 개인 하네스 배포본을 다른 PC에도 적용할지, 팀 공용 도구로 전환할지.

검증 예시·실행 결과는 `.ai-output/verification-251/`에 보관하고 PR에 요약한다. 시뮬레이션은 애플리케이션 테스트 통과의 증거가 아니다.
