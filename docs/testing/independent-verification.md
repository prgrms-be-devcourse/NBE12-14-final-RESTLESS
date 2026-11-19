# AI 독립 검증 절차

구현 완료 검토와 PR 승인 준비 시 [전용 스킬](../../tools/verification/skills/independent-verification/SKILL.md)을 **직접 읽고 실행한다**. 개인 전역 스킬 설치는 필요 없다. 기존 `code-review`를 사용해도 이 스킬의 사전 반례와 Assumptions 검토를 먼저/함께 수행한다. 테스트 실행·하네스 판정은 기존 절차를 대체하지 않는다.

## 사용하기

새 세션에 다음처럼 요구사항과 정확한 ref/SHA를 전달한다. 구현 보고서는 사전 반례 기록 뒤 전달한다.

```text
이슈 #N의 요구사항과 저장소 지침으로 검증하라.
기준: <base SHA>, 대상: <head SHA>.
tools/verification/skills/independent-verification/SKILL.md를 읽고 따른다.
먼저 precheck.md를 작성하라. 이후 diff와 구현 보고서를 대조하고 직접 검증하라.
```

이 파일과 스킬이 저장소의 배포본이다. 새 checkout에서도 위 상대 경로를 읽는다. 루트 AGENTS.md/CLAUDE.md는 개인 파일이므로 아래 보호 파일 연결 패치를 적용하기 전에는 자동 발견을 보장하지 않는다.

## 정책 결정 범위 (이번 PR의 절차 제안)

기존 루트 지침의 공개 계약·핵심 불변식 보호와 열린 질문의 사람 결정 원칙을 유지한다. 드롭 지침의 “문서에 없는 정책은 정해 진행”은 아래 구현 세부사항으로 한정하도록 제안한다. 사업 정책을 이 문서에서 확정하지 않는다.

| 구분 | 처리 |
|---|---|
| 관찰 가능한 동작·계약·불변식을 바꾸지 않는 내부 구현 선택 | 진행하고 근거를 기록 |
| 기존 합의 문서끼리 충돌하거나 요구사항 누락이 외부 동작을 바꿈 | 쟁점과 대안을 기록하고 영향 없는 작업만 진행; 영향받는 동작은 결정 전 확정하지 않음 |
| 금액·구매 자격·재고 소유·결제 UNKNOWN·환불·공개 계약·핵심 불변식·open-questions | 사람의 결정 필요. 기존 합의된 안전 규칙 유지. PROPOSED 표기는 승인 아님 |

## 보호 파일 연결

현재 편집 훅은 Codex의 `tools/harness/`와 에이전트 지침 수정을 하네스 브랜치에서도 막는다. 현재 `tools/harness/`는 git-ignore된 개인 도구다. [integration.patch](../../tools/verification/integration.patch)는 해당 개인 사본을 가진 환경을 위한 **미적용 제안**이며, 하네스가 없는 새 checkout에는 그대로 적용할 수 없다. 권한 있는 하네스 관리자가 일반 하네스 변경 절차로 적용하고 검사한다. 가드를 끄거나 우회하지 않는다.

패치는 하네스 검증 단계·구현자 예외·지시서와 드롭 정책 문구를 연결한다. 0줄 문맥 패치이므로 관리자는 `git apply --check --unidiff-zero tools/verification/integration.patch`로 검사하고 `git apply --unidiff-zero tools/verification/integration.patch`로 적용한다. 개인 루트 AGENTS.md에는 다음 포인터를 추가한다(버전 관리 밖이므로 별도 적용).

> 구현 검증·PR 승인 준비 전에 `tools/verification/skills/independent-verification/SKILL.md`를 읽고 따른다. 문서에 없는 정책의 결정 범위는 `docs/testing/independent-verification.md`를 따른다.

새 스킬 경로도 구현자가 수정하지 못하도록 보호 경로에 추가하는 것은 후속 하네스 변경이 필요하다. 현재 PR만으로 기술적 강제가 완성되지는 않는다.

## 사람이 PR에서 결정할 것

1. 위 구현 선택/사업 정책 경계를 채택할지.
2. 동일 구현자의 예외는 새 컨텍스트 검증자 또는 사람 검토를 추가해야 완료로 볼지.
3. 보호 파일 연결 패치와 새 경로 가드를 누가 적용할지.

검증 예시·실행 결과는 `.ai-output/verification-251/`에 보관하고 PR에 요약한다. 시뮬레이션은 애플리케이션 테스트 통과의 증거가 아니다.
