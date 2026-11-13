# 드롭 마무리 (트래픽 테스트 전)

- 담당: 드롭
- 시작: 2026-11-14
- 기준: 드롭 하네스 작업 순서 ([tools/harness/README.md](../../../tools/harness/README.md) 2절)

## 단계

| 순서 | 작업 | 상태 | 이슈 / PR |
|---|---|---|---|
| 1 | 드롭 등록 검증 보강: `closesAt > now`, `priceAmount >= 1`, `saleStatus`가 CLOSED를 반영 | 대기 | - |
| 2 | 드롭 탈퇴 제한 (`WithdrawalGuard`). `PAID` 주문은 막지 않는 제안 확정 필요 | 대기 | #41 |
| 3 | 내 주문 목록 `GET /api/me/orders` (공통 주문) | 대기 | - |
| 4 | 드롭 목록 검색·정렬 ([policy.md](../../domains/drop/policy.md) 3절 MVP) | 대기 | - |
| 5 | 트래픽 테스트 준비: k6 시나리오, 사후 점검 SQL, EXP-01·02 ([performance.md](../../testing/performance.md)) | 대기 | - |

1과 2는 병렬로 진행할 수 있다. 병렬로 하면 먼저 머지된 쪽을 기준으로 나머지를 다시 검사한다.
