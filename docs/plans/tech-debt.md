# 기술 부채와 알려진 한계

| 무엇 | 근거 | 제안 | 담당 |
|---|---|---|---|
| 문서와 코드가 어긋나도 드롭 문서만 자동으로 잡는다 | #79에서 불일치 26건을 손으로 정리. 문서 검사(`tools/harness/doc-check.py`)는 드롭 문서만 FAIL로 본다 | 다른 도메인 문서에도 `관련 코드` / `마지막 확인` 머리를 붙이고 검사 대상에 넣는다. CI 연결은 팀 합의 뒤 | 팀 |
| 통합 ERD(`docs/architecture/erd.md`)를 손으로 유지한다 | 2026-11-01 이후 7번 수정 | 마이그레이션을 적용한 DB에서 테이블·컬럼 목록을 뽑아 스키마 문서(예: docs/generated/db-schema.md, 아직 없음)를 만드는 스크립트. 마감 뒤 검토 | 팀 |
| 노션에서 옮긴 설계 문서의 이미지가 깨져 있다 | `docs/domains/ticketing/design.md` 7건, `docs/domains/auction/design.md` 1건 (`attachment:` 링크, 레포에 파일 없음) | 원본 이미지를 레포에 넣거나 링크를 지운다 | 티켓팅, 경매 |
| CI는 빌드만 한다 | `.github/workflows/ci.yml`: `./gradlew build`, `npm run build` | 문서 검사 같은 빠른 검사를 CI에 붙일지 팀이 정한다 | 팀 |
| DTO 위치가 모듈마다 다르다 | `api/dto` 5곳, `application/dto` 3곳 (ticketing은 둘 다) | 규칙 하나를 정해 [ADR-0002](../decisions/0002-common-conventions.md)에 적고 새 코드부터 따른다. 기존 파일은 옮기지 않는다 | 팀 |
| 드롭 INV-03(같은 SKU 대상)에 `INV-03` 표시가 붙은 테스트가 없다 | `backend/src/`에서 `INV-03` 검색 결과 0건 (2026-11-14) | 기존 테스트가 이미 검증하는지 확인하고, 아니면 테스트를 추가한다 | 드롭 |
| 드롭 수용 테스트는 서버 한 대, 적은 요청만 본다 | [tools/harness/README.md](../../tools/harness/README.md) 6절 | 트래픽 테스트(드롭 마무리 5단계)에서 확인한다 | 드롭 |
