# Codex 작업 지시서 템플릿 (일반)

드롭 수용 테스트 단계가 아닌 작업에 쓴다. 드롭 단계는 `tools/harness/codex-stage-spec.md`(개인 파일)를 쓴다.
`{...}`를 채운 뒤 `python3 tools/verification/bin/spec-lint.py <파일>`이 PASS인지 확인하고 넘긴다. 규칙의 이유는 [checklist.md](checklist.md) 6절에 있다.

---

# Codex 작업: {한 줄 요약} (이슈 #{ISSUE})

작업 폴더: {절대 경로} (git worktree면 그렇게 적는다)
브랜치: `{type}/{scope}-{ISSUE}-{slug}`
기준: {base ref} {base SHA 7자 이상}
폴더가 없으면 사용자가 만든다: `git -C {MAIN} worktree add {작업 폴더} -b {브랜치} {base SHA}`

이 지시서의 이슈 #{ISSUE}만 진행한다. 끝나면 멈춘다. 다른 이슈는 새 지시서로 받는다.
다른 폴더({다른 worktree 목록})는 읽지도 바꾸지도 않는다.
작업 전 `{MAIN}/AGENTS.md`를 읽고 따른다. git에 없는 개인 파일이라 worktree에는 없다. {드롭이면: `backend/src/main/java/com/restless/drop/AGENTS.md`도 직접 연다.}
{worktree면: `tools/harness`는 git에 없어 이 폴더에서 실행할 수 없다. 실행하지 않는다.}

## 배경

{문제, 관련 코드 위치, 앞선 검증의 지적}

## 할 일

1. 먼저 재현 테스트를 만든다. 수정 전 코드에서 단언 실패로 끝나는지 확인하고 출력을 보고서에 남긴다. 재현이 안 되면 멈추고 이유를 보고한다.
2. {수정 내용}
3. {문서 갱신}

## 하지 않을 것

- {범위 밖 변경}
- 기존 테스트의 단언을 약하게 하거나 `@Disabled` 처리
- 로그에 `paymentKey`나 예외 메시지 본문을 남기는 것, 메트릭 태그에 ID를 넣는 것

## 검증 (backend/에서)

- 새 테스트 클래스: `./gradlew test --tests '{클래스}'`
- 관련 모듈: `./gradlew test --tests 'com.restless.{module}.*' --tests 'com.restless.architecture.*'`
- `./gradlew build`

실행하지 않은 테스트를 통과했다고 쓰지 않는다.

## 마무리

- 커밋: `{type}({scope}): {description}`. 내 파일만 경로로 스테이징한다.
- 보고서: `{MAIN}/.ai-output/issues/{ISSUE}-report.md` (절대 경로). 변경 파일, 수정 전 실패 출력, 실행한 테스트와 결과, 남은 위험.
- push와 PR은 하지 않고 멈춘다. 독립 검증 뒤 PR을 만든다.
