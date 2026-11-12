# FanMeeting 정책

## 1. 범위

### P0 포함

행사 생성(관리자), 응모·취소·재응모, 응모 기간 자동 열기·마감, 결정적 추첨(당첨·예비), 당첨자 결제 기한(60분) 안 결제, 결제 포기, 결제 결과 보류, 기한 만료, 빈자리 예비 승격, 모집 완료, 관리자 summary.

### 만들지 말 것 (P0 범위 밖)

아래는 구현하지 않는다. 필요해 보여도 만들지 않고 TODO 주석도 남기지 않는다.

- Outbox/Inbox 테이블, Kafka, Redis, 캐시
- 실제 Common Payment·PG 연동, 환불 명령, `payment_version`
- 알림 발송(Push·이메일), 혜택 알림 구독, 기한 임박 알림
- 잔여석 판매(`LEFTOVER`), `leftover_*` 컬럼, `payment_deadline_at` 컬럼
- 랜덤 오픈 시각, 1차 알림, Schedule API, DRAFT 수정, 설정 잠금
- 이벤트 취소·환불 정산, `EVENT_COMPLETED`, `MANUAL_REVIEW` 자동 전환
- 응모 B안(시각 판정), Lease 갱신, 추첨 지수 backoff
- 보류 수동 처리 기록 테이블(로그로 대신), 관리자 화면
- 회원 정지, 본인인증, 티켓 보유자 한정
- 결제 후 참가 취소·환불
- 보류 수동 처리 API(`review-resolution`) — P1. P0는 30분 경고 로그까지만
- 실제 결제 모듈 수준의 결제 경합·복구: 승인 전 PROCESSING 전환, 결제창 재진입, 시계 차이, 결제 모듈 버전 대조 — P1에서 결제 담당과 정한다

enum에는 v0.8의 전체 값을 선언해도 된다. 전이는 이 폴더에 적힌 것만 구현한다.

## 2. 용어

| 용어 | 뜻 |
|---|---|
| Event | 팬미팅 행사 1개 (`fan_meeting_events`) |
| Application | 회원의 응모 1건 (`fan_meeting_applications`) |
| DrawRun | 행사의 추첨 실행 1건 (`fan_meeting_draw_runs`) |
| Selection | 추첨으로 뽑힌 당첨(WINNER)·예비(RESERVE) 1명 (`fan_meeting_selections`) |
| Order | 결제 기회 1건. 당첨자는 추첨 때, 예비자는 승격 때 생긴다 (`fan_meeting_orders`) |
| 결제 기한 | `issued_at + 60분`. **`Order.paymentDeadline()` 한 메서드에서만 계산한다** |
| 보류 | Order `PAYMENT_REVIEW`. 결제 결과를 몰라 자리를 지키는 상태 |
| 승격 | 빈자리만큼 예비자를 순번대로 `PAYMENT_PENDING`으로 바꾸고 Order를 만드는 것 |
| 승격 마감 | `promotion_closes_at`. 이 시각 이후 승격 없음 |
| 야간 | Asia/Seoul 23:00~09:00. 승격하지 않음 |
| 끝나지 않은 결제 시도 | 결과가 `READY`(시도만 생성) 또는 `UNKNOWN`인 시도 (실제 결제 모듈은 `PROCESSING`도 포함) |

## 3. 행사 생성

권한: ADMIN (6절). 결과: `SCHEDULED`.

검증(위반 시 400 `INVALID_FAN_MEETING_SETTINGS`, 메시지에 어긴 규칙):

- `winner_limit >= 1`, `reserve_limit >= 0`, `fee_amount >= 0`
- `entry_opens_at > now`
- `entry_closes_at = entry_opens_at + 10분` (입력받지 않음)
- `first_notice_scheduled_at <= entry_opens_at`
- `meeting_at >= entry_closes_at + 48시간`
- `promotion_closes_at` 미입력이면 `meeting_at - 24시간`
- `entry_closes_at + 2시간 <= promotion_closes_at <= meeting_at - 3시간`

## 4. 정책 요약

| 영역 | 정책 |
|---|---|
| 응모 기간 | `[entry_opens_at, entry_closes_at)`, 10분 고정 |
| 열기 | Open Worker가 `entry_opens_at` 도달 시 `OPEN`. 서버가 멈췄다 돌아와 `entry_closes_at`까지 지났으면 열지 않고 `SCHEDULED`로 두고 오류 로그 `FANMEETING_OPEN_MISSED` (운영자 처리) |
| 마감 | Close Worker가 `CLOSED`로 바꾸며 DrawRun(`PENDING`, 새 seed) 생성 |
| 마감 판정 | **커밋 기준.** 공유 잠금 안에서 `now < entry_closes_at`을 확인했고 마감 Worker의 CLOSED 전환보다 먼저 커밋된 응모만 성공·추첨 후보. 서버 도착 시각으로 소급하지 않는다 |
| 응모 제한 | 회원·행사당 Application 1개. 취소(`WITHDRAWN`) 후 같은 행으로 재응모 |
| 가입 시각 자격 | 설정 ON이면 가입 시각(`users.created_at`) `>= first_notice_scheduled_at`인 회원은 응모 불가. 기본 OFF(7절) |
| 추첨 | HMAC-SHA256 순위 키 결정적 추첨. 앞에서 `winner_limit`명 WINNER, 다음 `reserve_limit`명 RESERVE. 탈퇴 회원은 계산 시 제외(응모 행은 유지) |
| 결제 금액 | Order 생성 시점 `fee_amount` 스냅샷. 요청 본문의 금액·회원·기한은 무시 |
| 결제 기한 | `issued_at + 60분`. `approvedAt <= 기한`이면 기한 안(같으면 기한 안) |
| 결제 포기 | 결제 시작을 한 번도 요청하지 않은(`attempt_requested_at IS NULL`) `PENDING_PAYMENT` 주문만. 되돌리기 없음 |
| 결과 모름 | `UNKNOWN`/`PROCESSING`은 실패로 처리하지 않고 보류(`PAYMENT_REVIEW`). 보류 중 자리는 만료되지 않고 넘어가지 않는다 |
| 만료 | 결제 모듈이 최종(`finalDecision = true`)이라고 답했을 때만. 팬미팅 시계로 최종 여부를 판단하지 않는다 |
| 보류 장기화 | 30분 경과 시 경고 로그 1회(보류 회차당). 자동 확정·탈락 없음. 수동 처리는 P1 |
| 늦은 승인·금액 불일치 | 확정하지 않고 `REFUND_PENDING` 표시. 실제 환불 명령은 보내지 않는다 |
| 승격 | 빈자리만큼 예비 순번대로. 야간(23:00~09:00 Asia/Seoul) 금지, `now + 60분 <= promotion_closes_at`일 때만 |
| 승격 마감 기본값 | `meeting_at - 24시간` |
| 모집 완료 | 보류 0건이고 ① 확정 인원 `>= winner_limit` ② 예비·결제 대기 모두 0 ③ 승격 마감이 지났고 결제 대기 0 중 하나. 남은 예비는 `NOT_NEEDED`. 야간에도 판정한다. `REFUND_PENDING`은 완료를 막지 않는다 |
| 알림 | 실제 발송 없음. 추첨 최종화·승격 커밋 뒤 `NotificationRequestPort`에 기록만 |
| 개인 정보 | `random_seed`, `lease_token`, 다른 회원 정보는 응답·로그에 내보내지 않는다 |

## 5. 탈퇴 확인 `hasActiveParticipation(userId)` (FanMeeting Facade)

그 회원의 Order가 `PENDING_PAYMENT`, `PAYMENT_REVIEW`, `REFUND_PENDING`이거나, `PAID`이면서 해당 Event의 `meeting_at`이 아직 안 지났으면 `true`. 인증 모듈 연결은 하지 않는다(메서드만 제공).

알려진 틈: 탈퇴 확인(`false`)과 승격·추첨 최종화가 동시에 일어나면 탈퇴한 사람에게 주문이 생길 수 있다. 결제를 못 하므로 기한 뒤 만료 → 승격으로 처리된다(정원·자격 정합성 문제 없음, 자리만 늦게 참).

## 6. 관리자 권한

- `/api/admin/**`(생성, summary)는 `role = ADMIN`인 회원만 호출한다. 인증 모듈의 보안 설정이 경로 단위로 막고, 권한이 없으면 403 `FORBIDDEN`(팀 공통 오류 형식 `{code, message}`), 로그인하지 않았으면 401 `UNAUTHORIZED`다([공통 API](../../contracts/api/common.md)).
- 팬미팅 컨트롤러는 권한을 다시 검사하지 않는다. 로그인 회원 ID는 `@LoginMember Long userId`로 받는다.
- **[팀 미확정]** 판매자(SELLER) 역할이 생기면 주최자에게도 열지는 팀 결정을 따른다.

## 7. 설정값 (설정 파일)

| 설정 | 값 |
|---|---|
| Open/Close Worker | 1초 |
| Draw Worker | 5초 |
| 추첨 lease | 2분 |
| 만료 Worker | 1분 |
| 승격 Worker | 30초 |
| 결제 기한 | 60분 |
| 보류 경고 | 30분 |
| 야간 승격 중지 | 23:00~09:00 Asia/Seoul |
| 승격 마감 기본값 | `meeting_at - 24시간` |
| 일반 트랜잭션 timeout | 5초 |
| 추첨 최종화 timeout | 30초 |
| 응모 재시도 | 최대 3회, 10~50ms 무작위 대기 |
| 가입 시각 검사 `fanmeeting.eligibility.joined-at-check.enabled` | 기본 OFF. 가입 시각은 `users.created_at`(`MemberPort.joinedAt`). 시연·측정에서 켤지는 담당자가 정한다. 테스트 C21·C50은 ON으로 돌린다 |
