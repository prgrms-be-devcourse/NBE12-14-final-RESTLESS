# FanMeeting 도메인

담당자: 홍승연

팬미팅 응모 → 추첨(당첨·예비) → 결제 기한 안 결제 → 빈자리 예비 승격 → 모집 완료까지를 다룬다. 이전 쿠폰(선착순 발급) 도메인을 대체한다([폐기한 가정 LEGACY-11](../../decisions/superseded.md)).

- 상태: P0 설계 동결 (2026-11-10). 구현 중 문제가 나오면 "P0에서 실제로 구현할 경로에서 생기는가?"로 판단하고, 아니면 P1로 넘긴다.
- 범위: P0(MVP) = 11/11 ~ 11/17
- 출처: 담당자 P0 구현 명세(`FANMEETING_P0_SPEC.md`, 담당자 개인 문서로 저장소에는 없음). 결정 이유와 변경 이력은 담당자 설계 문서(`fan_meeting_backend_design_v0_8.md`, `DESIGN_DECISIONS_PENDING.md`, `P0_FIX_REVIEW.md`)에 있다. 이 폴더와 v0.8이 다르면 이 폴더가 P0 기준이고, 이름(테이블·컬럼·상태·API)은 v0.8과 같다.
- **[팀 미확정]** 표시가 있는 값은 가정값으로 시작하고, 팀 결정이 나오면 바꾼다.

| 문서 | 내용 |
|---|---|
| [policy.md](policy.md) | 범위, 용어, 생성 검증, 응모·추첨·결제·승격 정책, 설정값 |
| [invariants.md](invariants.md) | INV-FM-01 ~ INV-FM-17 |
| [state-machines.md](state-machines.md) | Event, Selection, Order 상태와 Selection–Order 짝 |
| [transactions.md](transactions.md) | 잠금 규칙과 처리별 트랜잭션 |
| [data-model.md](data-model.md) | Flyway DDL, 마이그레이션, 정합성 SQL |
| [ports.md](ports.md) | MemberPort·NotificationRequestPort, 결제 모듈 연결(`PaymentResultHandler`) |
| [test-scenarios.md](test-scenarios.md) | C01 ~ C56 테스트 시나리오, 추첨 재현성 |

관련: [API 계약](../../contracts/api/fanmeeting.md), [이벤트 계약](../../contracts/events/fanmeeting.md), [통합 ERD](../../architecture/erd.md)

## 구현 순서 (MVP 일정)

| 날 | 할 일 | 테스트 |
|---|---|---|
| 1 (11/11) | DDL(Flyway), Event 생성·조회, 주최자·관리자 권한, 응모·취소·재응모 | 수동 호출, C41 |
| 2 (11/12) | 응모 재시도·실패 처리, Open/Close Worker | C01~C03, C45, C52 |
| 3 (11/13) | 추첨(바이트 규칙), MemberPort 가짜, 알림 기록 | C05, C07, C08, C25, C33(추첨), C36, C55, 재현성 고정 정답 |
| 4 (11/14) | 결제 Handler(공통 결제 모듈), 결과 반영, 포기 | C09~C13, C26~C30, C42~C44, C47, C49, C51, C56 |
| 5 (11/15) | 만료, 승격·모집 완료, summary | C14, C15, C22, C23, C31, C32, C33(승격), C48 |
| 6 (11/16) | 보류 30분 경고, `hasActiveParticipation`, 처음부터 끝까지 관통 | C21, C33, C34, C50, 불변식 SQL |
| 7 (11/17) | S1 측정 | C46 (`-Pstress`) |

밀리면 빼는 순서: C21·C50 → 3,000 RPS 측정 → 알림 기록(C36) → 야간 중지(C32). 승격·포기·승격 마감·관리자 권한·소속 확인은 빼지 않는다.

## 구현 후 꼭 사람이 줄 단위로 읽을 곳

1. 응모 트랜잭션: 잠금 없는 조회 → INSERT, 실패 시 롤백·재시도 ([transactions.md 응모](transactions.md#3-응모))
2. Handler 판정 순서와 현재 시도(`payment_id`)·`approved_attempt_id` ([결제 결과 반영](transactions.md#7-결제-결과-반영-fanmeetingpaymentresulthandler))
3. 만료 Worker가 결제 중인 주문을 건드리지 않는지 ([만료 Worker](transactions.md#8-만료-worker-1분))
4. 승격의 "잠그고 다시 센다" ([승격](transactions.md#10-승격-worker-30초--모집-완료))
5. 결제 준비와 포기의 경합 ([결제 시작](transactions.md#6-결제-시작-공통-결제-api), [결제 포기](transactions.md#9-결제-포기))
