# Fan Meeting 불변식

어떤 동시 요청 상황에서도 반드시 지켜야 하는 조건이다. 테스트 이름이나 주석에 `FM-INV-xx`를 붙여 추적한다.

| ID | 불변식 | 지키는 방법 |
|---|---|---|
| FM-INV-01 | 회원당 행사별 응모는 하나다 | `UNIQUE(event_id, user_id)` |
| FM-INV-02 | 마감 뒤 저장된 응모는 0건이다 | 응모는 행사 행 공유 잠금, 마감은 배타 잠금으로 직렬화한다 |
| FM-INV-03 | 같은 입력이면 같은 추첨 결과다 | HMAC-SHA256 결정적 순위, seed는 마감 때 한 번 저장한다. 추첨 실행은 행사당 하나(`UNIQUE(event_id)`), lease token으로 늦은 Worker의 저장을 막는다 |
| FM-INV-04 | 확정 + 결제 대기 ≤ 정원 | 승격과 잔여석 구매는 행사 행 배타 잠금 안에서 다시 세고 빈자리만큼만 만든다 |
| FM-INV-05 | 기한 안에 승인된 결제는 참가 확정된다 | 만료 전에 결제 결과를 조회하고, 확실히 미승인일 때만 만료한다 |
| FM-INV-06 | 결과가 불명확한 자리는 넘어가지 않는다 | 보류(`PAYMENT_REVIEW`) 중에도 결제 대기로 정원을 차지한다 |
| FM-INV-07 | 주문 금액 = 참가비 스냅샷 = 승인 금액, 같은 결제 시도는 한 번만 반영한다 | 금액은 서버 값만 쓴다 |

DB 제약으로 함께 지키는 것:

- 응모 하나는 최대 한 번 선발된다: `fan_meeting_selections` `UNIQUE(application_id)`
- 같은 추첨에서 (구분, 순번)은 중복되지 않는다: `UNIQUE(draw_run_id, selection_type, selection_rank)`
- 선발 하나에 주문 하나: `fan_meeting_orders` `UNIQUE(selection_id)`
- 다른 행사·다른 회원의 응모·선발을 가리키지 못한다: 복합 FK ([통합 ERD](../../architecture/erd.md) 3절)

원본은 전체 불변식이 18개라고 하지만, 저장소에는 핵심 7개만 옮겼다. 나머지는 담당자가 추가한다.
