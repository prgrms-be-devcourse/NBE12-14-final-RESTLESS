# FanMeeting 외부 Port와 결제 모듈 연결

## 1. Port

```java
interface MemberPort {
    Instant joinedAt(Long userId);                         // users.created_at. 조회 실패면 예외
    Set<Long> activeMemberIds(Collection<Long> userIds);   // 탈퇴하지 않은 회원 (users.status = ACTIVE)
}

interface NotificationRequestPort { void request(NotificationRequest request); }
```

결제는 Port를 두지 않는다. 팬미팅이 결제 모듈의 `PaymentResultHandler`를 구현하고, 결제 모듈이 부른다(2절).

## 2. 결제 모듈 연결 (`PaymentResultHandler`, ADR-0003)

`FanMeetingPaymentResultHandler`가 `PayableType.FAN_MEETING`을 맡는다. 결제 대상 id는 주문 id(`fan_meeting_orders.order_id`)다. 처리 흐름은 [transactions.md 6·7절](transactions.md#6-결제-시작-공통-결제-api).

| 결제 모듈이 부르는 때 | Handler | 팬미팅이 하는 일 |
|---|---|---|
| 결제 준비 `POST /api/payments` | `load` | 결제 대기 주문이면 회원·금액·행사 제목·기한을 돌려주고 결제 준비 요청 시각 기록 |
| 승인 요청 `POST /api/payments/confirm` | `onApprovalStarting` | 기한 전이면 이 시도를 현재 시도로 기록 |
| PG 승인 | `onApproved` | 기한·금액·현재 시도 확인 후 확정, 아니면 거절(결제 모듈이 보상 취소) |
| PG 미승인 확정, READY 시도 만료 | `onFailed` | 기한 남으면 다시 결제 가능, 지났으면 만료 |
| PG 결과 모름 | `onUnknown` | 보류 |
| 보상 취소 완료 | `onRefunded` | 환불 대기 → 환불됨 |

결제 모듈과 맞춘 규칙(결제 담당 공유, 2026-11-13):

1. **Handler는 결제 모듈의 트랜잭션 안에서 실행한다**(`MANDATORY`). 결제 모듈이 결제 행을 먼저 잠그고 부른다. Handler는 자기 행만 잠그고 결제 행을 다시 잠그지 않는다.
2. **금액·기한은 `load()`가 돌려준 값을 쓴다.** 요청 본문의 금액·회원·기한은 쓰지 않는다.
3. **같은 대상에 READY 시도가 있는데 다른 키로 오면** 결제 모듈이 이전 READY를 `FAILED(SUPERSEDED)`로 닫고 새 시도를 만든다. 진행 중인 시도는 항상 하나다.
4. **READY 시도의 기한이 지나면** 결제 모듈이 `onFailed`(승인 시작 전)로 알려 준다. 결과를 따로 묻는 API는 없다.
5. 결제 멱등 키는 대소문자를 구분한다.

## 3. 테스트

- 공통 결제 API와 `FakePgClient`로 끝까지 실행한다(`FanMeetingPaymentApiTest`). 승인·실패·모름·지연은 `willApprove`, `willReject`, `willTimeout`, `willDelay`로 만든다.
- 결제 모듈이 걸러 주어 API로는 만들 수 없는 경우(기한 경계, 같은 승인 두 번, 다른 시도의 승인, 만료 뒤 승인)는 트랜잭션을 열고 Handler를 직접 부른다.

## 4. Member Adapter와 알림 기록

- `MemberPort`는 회원 모듈의 `MemberService.joinedAt`, `MemberService.activeMemberIds`로 구현한다(`MemberServiceMemberAdapter`). 회원 Repository를 직접 쓰지 않는다.
- 테스트는 가짜 대신 실제 회원 행을 쓴다(탈퇴는 `users.status = WITHDRAWN`, 가입 시각은 `users.created_at`). 조회 예외(C50)만 `@MockitoSpyBean`으로 만든다.
- `NotificationRequestPort`는 구조화 로그 `FANMEETING_NOTIFICATION type=… eventId=… orderId=… paymentDeadline=…`로 구현한다. 회원 정보는 넣지 않는다.
