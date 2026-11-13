# FanMeeting 테스트 시나리오

실행 명령은 [testing/strategy.md](../../testing/strategy.md)를 따른다.

## 1. 공통

- 동시성·통합 테스트는 **실제 MySQL(Testcontainers)**, 격리 수준 `REPEATABLE READ`. H2 금지.
- 시각은 테스트용 가변 `Clock`(`advance(60분)`). 실제로 기다리지 않는다.
- 테스트에서는 `@Scheduled`를 끄고 Worker의 1회 실행 메서드를 직접 호출한다.
- 동시성 테스트는 **결과 불변식만 검사**한다(누가 먼저 끝났는지에 기대지 않음).
- 위험한 순서는 latch로 강제한다(C03, C14, C23, C29, C43).
- 반복 경합(100~1,000회)은 `-Pstress` 프로필에서만.
- 매 시나리오 테스트 끝에 INV-FM-07, 09, 14, 15를 SQL로 확인한다.

## 2. 목록

| 번호 | 시나리오 | 기대 |
|---|---|---|
| C01 | 같은 회원 최초 응모 2건 동시 | Application 1개, 다른 요청 `ALREADY_APPLIED` |
| C02 | WITHDRAWN에서 재응모 2건 동시 | 하나만 APPLIED |
| C03 | 응모와 마감 동시. 시각 확인을 통과한 응모가 잠금 대기로 마감 시각 뒤에 커밋되는 경우 포함 (latch) | CLOSED 이후 커밋된 응모 0, 그 응모는 성공·추첨 후보 |
| C05 | Draw Worker 2개 동시 Claim | 유효 token 1개 |
| C07 | 만료된 token으로 늦은 최종화 | 저장 0 |
| C08 | 최종화 중 DB 오류 | 전체 롤백 |
| C09 | 같은 승인 2회 전달 | 한 번만 반영 |
| C10 | 결제 실패 후 기한 남음 | 새 키로 새 시도 가능 |
| C11 | UNKNOWN으로 기한 경과 | REVIEW 유지, 그 자리 승격 없음, 다른 빈자리 승격은 진행 |
| C12 | `approvedAt == 기한` | PAID |
| C13 | `approvedAt == 기한 + 1ms` | REFUND_PENDING, EXPIRED |
| C14 | 승인 기록(`recordResult`)·반영과 만료 Worker 동시 (latch, 기한 경계) | 한 결과로 수렴. 승인이 먼저면 PAID, 만료 판정이 먼저면 시도가 닫혀 승인 거절. **EXPIRED인데 승인된 시도 0건** (INV-FM-10) |
| C15 | 승격 Worker 2개 동시 | 승격 합계 = vacancy |
| C21 | 가입 시각 검사 ON, 가입 기준 시각 이후 가입 회원 응모 | 403 `APPLICATION_NOT_ELIGIBLE` |
| C22 | 승격된 예비 일부 재만료 | 다음 순위 승격, 정원 유지 |
| C23 | 승격과 결제 승인 동시 (latch) | INV-FM-07 유지 |
| C25 | 다른 행사의 `event_id` 또는 다른 회원의 `user_id`로 Selection/Order 저장 | 복합 FK 위반, 롤백. 물리 FK가 없으면 [정합성 SQL](data-model.md#4-정합성-sql) 0건 |
| C26 | 다른 회원 orderId로 결제 시작·포기 | 404, 상태 변화 없음 |
| C27 | 요청 금액 변조 / 승인 금액 불일치 | 서버 금액 사용 / REFUND_PENDING, EXPIRED |
| C28 | 결제 시작 전 포기 | CANCELLED, DECLINED, 다음 회차에 예비 1명 승격 |
| C29 | 포기와 결제 시작 동시 (latch) | 하나만 성공, 다른 쪽 409, INV-FM-17 |
| C30 | 결제 시작 후 포기 (시도 FAILED여도) | 409 `DECLINE_NOT_ALLOWED` |
| C31 | 승격 마감 60분 이내 빈자리 / 마감 후 결제 대기 0 / 마감 후 보류 남음 | 승격 0 / 모집 완료·남은 예비 NOT_NEEDED / 완료 안 됨 |
| C32 | 23:30 빈자리 | 승격 0, 09:00 이후 승격 |
| C33 | 탈퇴자 처리 | 탈퇴 응모자는 선발 안 되고 응모 행 유지, 예비 1순위 탈퇴 시 2순위부터 승격·1순위 NOT_NEEDED, `hasActiveParticipation` 참/거짓 |
| C34 | 보류 30분 경과 | 경고 1회(다음 회차에 다시 안 남음), 자동 확정·탈락 없음 |
| C36 | 알림 요청 | 최종화 시 당첨자 수만큼 기록, 롤백되면 기록 0 |
| C41 | 일반 회원이 주최자 API(생성)·관리자 API(summary) 호출 | 403 `FORBIDDEN`, 상태 변화 없음 |
| C42 | 다른 행사의 eventId + 내 orderId로 결제 시작·포기 | 404 `ORDER_NOT_FOUND`, 어느 Event도 상태 변화 없음 |
| C43 | 같은 주문에 서로 다른 Idempotency-Key로 결제 시작 동시 2건 (latch) | 시도 1개 생성(201), 다른 쪽 409 `PAYMENT_ATTEMPT_IN_PROGRESS` |
| C44 | ① READY 시도가 있는 주문에 새 키 ② 주문 `PENDING_PAYMENT`에서 같은 키 재요청(시도 READY·FAILED) ③ 주문 `PAID`·`PAYMENT_REVIEW`에서 같은 키 재요청 ④ 이전 시도 FAILED 뒤 새 키 ⑤ 키 A 시도가 FAILED가 된 뒤 키 A 재요청 | ① 409 `PAYMENT_ATTEMPT_IN_PROGRESS`, 시도 수 그대로 ② 그 시도의 현재 상태(READY만 URL, 200) ③ 409 `ORDER_NOT_PAYABLE` / 409 `PAYMENT_UNDER_REVIEW`, 결제 모듈 호출 없음 ④ 새 시도(201) ⑤ 같은 FAILED 시도(200), 새 시도 안 생김 |
| C45 | 같은 회원 첫 응모 2건·10건 동시 | Application 1개, 나머지 `ALREADY_APPLIED`, 5xx 0 |
| C46 | 서로 다른 회원 1,000명 첫 응모 동시 (`-Pstress`) | **최종 정합성:** Application 정확히 1,000건, 회원당 1건, 성공 응답 수 = 커밋 수. **재시도:** 1062·1213·1205가 나도 재시도로 성공해 `APPLICATION_BUSY` 0건(0이 아니면 건수와 원인 기록). 교착·잠금 시간 초과 발생 수는 **지표로 기록**(실패 조건 아님) |
| C47 | `REFUND_PENDING` 주문에 같은 승인 2회 / 다른 시도의 승인 | 상태·`approved_attempt_id` 변화 없음 / 이중 결제 기록, 상태 유지 |
| C48 | `PAYMENT_REVIEW` 상태에서 UNKNOWN 반복, 30분 경과 | `review_started_at` 그대로, 30분에 경고 1회 |
| C49 | `EXPIRED` 뒤 기한 안 `approvedAt`을 가진 승인 도착 | `REFUND_PENDING`, Selection `EXPIRED`, `FANMEETING_APPROVAL_AFTER_FINAL` 로그, 정원 변화 없음 |
| C50 | 가입 시각 검사 ON + MemberPort 조회 예외 | 503 `MEMBER_LOOKUP_FAILED`, Application 0 |
| C51 | `deliver` 중 Handler 예외 → `deliver` 재호출 | 시도 기록 유지, 재호출로 한 번만 반영 |
| C52 | 응모 재시도 3회 모두 잠금 시간 초과(강제) | 503 `APPLICATION_BUSY`, Application 0, 트랜잭션 남은 것 없음 |
| C55 | 같은 DrawRun에 최종화 두 번 (완료 뒤 같은 token으로 재시도, 다른 Worker의 늦은 최종화) | 두 번째는 저장 0, 예외 없이 종료, Selection·Order 수 변화 없음 |
| C56 | 운영 마이그레이션 검증: 운영 설정의 `spring.flyway.locations`(가짜 폴더 제외)로 빈 MySQL에 migrate 후, 가짜 엔티티를 뺀 JPA `validate` (Testcontainers, 슬라이스 테스트) | migrate 성공, `fake_*` 테이블 없음, validate 통과. **전체 앱 기동은 검증하지 않는다**: P0에는 운영용 `PaymentPort` 구현이 없어 운영 프로필 전체 기동은 실패하는 게 정상이다 |

번호: C24는 B안(P1), C37~C40은 잔여석(P1)이 쓴다. C35·C53·C54·C57·C58·C59는 수동 처리·실제 결제 수준 경합이라 P1로 넘겼다. 새 테스트는 C60부터.

## 3. 추첨 재현성

- **고정 정답:** seed = `0x00, 0x01, …, 0x1F`(32바이트), 후보 `application_id` 1~30,000, `winner_limit` 300, `reserve_limit` 100. 결과 해시 = WINNER 순서대로, 이어서 RESERVE 순서대로 `application_id`를 8바이트 big-endian으로 이어 붙인 바이트의 SHA-256 hex. **기대 값은 3일차에 한 번 계산해 테스트에 박는다.**
- **재시작 후 동일:** 계산 중 Worker 중단 → lease 만료 → 다른 Worker 재계산 결과가 같음(탈퇴 집합 고정).
