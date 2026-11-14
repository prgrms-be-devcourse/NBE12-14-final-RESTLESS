# FanMeeting 불변식

단위, 통합, 동시성 테스트의 기준이다. 테스트 이름이나 주석에 `INV-FM-xx`를 붙여 추적한다. 매 시나리오 테스트 끝에 INV-FM-07, 09, 14, 15를 SQL로 확인한다([test-scenarios.md](test-scenarios.md)).

| ID | 불변식 |
|---|---|
| INV-FM-01 | 회원 1명은 행사 1개에 Application 최대 1개 |
| INV-FM-02 | `CLOSED` 이후 커밋된 응모·취소 0건 |
| INV-FM-03 | 가입 기준 시각 이후 가입한 회원의 응모 0건 (가입 시각 검사 ON일 때) |
| INV-FM-04 | 행사당 DrawRun 최대 1개, `COMPLETED` 결과는 덮어쓰지 않음 |
| INV-FM-05 | 같은 seed·후보 집합·알고리즘 버전이면 같은 추첨 결과 |
| INV-FM-06 | WINNER ≤ `winner_limit`, RESERVE ≤ `reserve_limit`, Application 하나는 최대 한 번 선발 |
| INV-FM-07 | **`CONFIRMED + PAYMENT_PENDING ≤ winner_limit`** |
| INV-FM-08 | 승격된 RESERVE의 최대 순번보다 앞 순번인 `WAITING` 없음 |
| INV-FM-09 | Selection·Order는 [Selection–Order 짝 표](state-machines.md#5-selectionorder-짝)의 조합으로만 존재 |
| INV-FM-10 | **기한 안에 승인된 Order의 Selection은 `CONFIRMED`** (기한 내 결제자 자격 상실 0). 결제 중인 시도가 있는 주문은 결제 모듈이 최종 실패를 알려 줄 때만 만료한다([transactions.md 7·8절](transactions.md#7-결제-결과-반영-fanmeetingpaymentresulthandler)). 만료 뒤 승인은 결제 모듈 계약 위반으로 거절한다 |
| INV-FM-11 | 보류 중인 자리는 만료되지 않고 다른 사람에게 넘어가지 않음 |
| INV-FM-12 | Order 금액 = 생성 시점 참가비 = 승인 금액 (다르면 확정하지 않음) |
| INV-FM-13 | 같은 결제 시도로 주문 상태가 두 번 바뀌지 않음. (P0 범위: 상태 변경. 이중 결제 기록의 중복 제거는 P1 Inbox) |
| INV-FM-14 | Selection·Order의 `event_id`와 `user_id` = 가리키는 부모의 `event_id`와 `user_id` |
| INV-FM-15 | 모집 완료 시 `PAYMENT_PENDING`, `PAYMENT_REVIEW`, `WAITING` 0건 |
| INV-FM-16 | 승격 Order 중 `issued_at + 60분 > promotion_closes_at`인 것 0건, 야간에 발급된 것 0건 |
| INV-FM-17 | `DECLINED` Selection의 Order는 `attempt_requested_at`이 NULL |

AGENTS.md의 핵심 불변식 중 이 도메인에 해당하는 것:

- 정원 초과 금지 → INV-FM-07
- 결과를 모르는 PG 응답을 실패로 처리하지 않음 → INV-FM-10, INV-FM-11
- 중복 요청이 중복 주문을 만들지 않음 → INV-FM-01, INV-FM-13, `UNIQUE(selection_id)`
