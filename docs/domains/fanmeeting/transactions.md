# Fan Meeting 트랜잭션과 시퀀스

공통 규칙:

- 동시성 제어의 기준은 행사(`fan_meeting_events`) 행이다. 잠금 순서는 고정한다 (FM-NFR-05).
- 결제 호출과 결제 결과 조회는 DB 잠금을 쥔 트랜잭션 밖에서 실행한다.
- 주문과 선발 상태는 같은 트랜잭션에서 바꾼다.

## 1. 응모와 마감

```mermaid
sequenceDiagram
    actor U as 회원
    participant F as 팬미팅 서버
    participant DB as MySQL
    participant W as 마감 Worker
    U->>F: 응모 요청
    F->>DB: 행사 행 공유 잠금 (열림·시각 확인)
    F->>DB: 내 응모 행 잠금 후 저장
    F->>DB: COMMIT
    F-->>U: 응모 성공
    W->>DB: 행사 행 배타 잠금 (진행 중 응모가 끝날 때까지 대기)
    W->>DB: CLOSED + 추첨 실행 생성 후 COMMIT
```

응모끼리는 공유 잠금이라 서로 기다리지 않는다. 마감만 배타 잠금으로 진행 중 응모를 기다린 뒤 닫는다.

## 2. 추첨

```mermaid
sequenceDiagram
    participant W as 추첨 Worker
    participant DB as MySQL
    participant A as 인증
    W->>DB: 행사·추첨 실행 잠금, lease token 발급 후 COMMIT
    W->>DB: 응모 목록 조회 (트랜잭션 밖)
    W->>A: 탈퇴하지 않은 회원 조회
    W->>W: HMAC 순위 계산
    W->>DB: 행사·추첨 실행 잠금, token 일치 확인
    W->>DB: 선발·당첨 주문 저장, 추첨 완료 후 COMMIT
```

다른 Worker가 lease를 가져간 뒤 늦게 끝난 Worker는 token이 달라 저장하지 못한다.

## 3. 결제 및 참가 확정

```mermaid
sequenceDiagram
    actor U as 당첨자
    participant F as 팬미팅
    participant P as 공통 결제
    participant DB as MySQL
    U->>F: 결제 시작
    F->>DB: 주문 잠금 · 기한 확인 · 결제 요청 표시 후 COMMIT
    F->>P: 결제 시도 생성 (트랜잭션 밖)
    alt 기한 안 승인
        P-->>F: PaymentSucceeded (approvedAt)
        F->>DB: 주문 PAID · 선발 CONFIRMED
    else 실패 확정
        P-->>F: PaymentFailed
        F->>DB: 기한 남으면 재시도 가능
    else 결과 불명확
        P-->>F: PaymentOutcomeUnknown
        F->>DB: PAYMENT_REVIEW · 자리 유지
    end
```

## 4. 결제 포기와 결제 시작의 경합

```mermaid
sequenceDiagram
    actor T1 as 탭 1 (결제)
    actor T2 as 탭 2 (포기)
    participant F as 팬미팅
    participant DB as MySQL
    T1->>F: 결제 시작
    T2->>F: 포기
    F->>DB: 주문 잠금 (T1 먼저)
    F->>DB: 결제 요청 표시 후 COMMIT
    F->>DB: 주문 잠금 (T2)
    F-->>T2: 409 결제를 시도한 주문은 포기할 수 없음
```

둘 다 주문 행을 먼저 잠그므로 둘 중 하나만 성공한다.

## 5. 만료와 예비 승격

```mermaid
sequenceDiagram
    participant E as 만료 Worker
    participant P as 공통 결제
    participant DB as MySQL
    participant R as 승격 Worker
    E->>P: 기한 지난 주문의 결제 결과 조회 (잠금 밖)
    P-->>E: 미승인 확정
    E->>DB: 주문·선발 EXPIRED
    R->>DB: 행사 행 배타 잠금
    R->>DB: 확정 + 결제 대기 수를 다시 셈
    R->>DB: 빈자리만큼 예비 순번대로 승격 + 주문 생성
```

결제 결과가 불명확하면 기한이 지났다는 이유만으로 만료하지 않는다.

## 6. 잔여석 구매 (P1)

```mermaid
sequenceDiagram
    actor U as 미선발 응모자
    participant F as 팬미팅
    participant DB as MySQL
    U->>F: 잔여석 구매
    F->>DB: 행사 행 배타 잠금 (구매끼리 한 명씩)
    F->>DB: 판매 시간·야간·응모 자격 확인
    F->>DB: 확정 + 결제 대기 수를 다시 셈
    alt 자리 있음
        F->>DB: 선발 + 주문(10분) 생성 후 COMMIT
        F-->>U: 구매 성공
    else 자리 없음
        F-->>U: 409 매진
    end
```

남은 좌석 수를 따로 저장하지 않는다. 만료·포기된 자리는 다음 구매자가 셀 때 자동으로 돌아온다.
