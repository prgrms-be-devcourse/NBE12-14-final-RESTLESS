# Drop 트랜잭션 경계

PG 호출 중에는 DB 트랜잭션을 열어 두지 않는다.

## TX1. 재고 예약 + 주문 생성

하나의 MySQL 트랜잭션에서:

1. 사용자별 활성 구매 거래 검증, 제어
2. SKU 재고 조건부 예약 (Notion 원본은 이 단계가 1번이다. 경합이 심한 재고 행의 잠금 시간을 줄이려고 슬롯 확인 뒤로 옮겼다)
3. DropReservation 생성
4. 공통 Order 생성
5. OrderItem, 배송, 가격 스냅샷 생성
6. Commit

하나라도 실패하면 전체 롤백한다.

## TX2. PG 승인 시작

짧은 MySQL 트랜잭션에서:

1. 예약 상태와 만료 검증
2. RESERVED → CONFIRMING
3. Order → PAYMENT_CONFIRMING
4. Payment 승인 시도 상태 기록
5. Commit

그다음 트랜잭션 밖에서 PG 승인 API를 호출한다.

## TX3. 구매 확정

PG 승인이 확인된 뒤 하나의 트랜잭션에서:

1. 결제 승인 결과 기록
2. 예약 CONFIRMED
3. reserved -1, sold +1
4. 주문 PAID
5. 구매 슬롯 해제 (같은 사용자가 다시 구매할 수 있게)
6. Commit

비핵심 후속 작업은 커밋 후 Spring Event Listener로 처리한다. Outbox 기록은 넣지 않는다 ([ADR-0001](../../decisions/0001-event-handling.md)).

## TX4. 실패, 만료 예약 반환

하나의 트랜잭션에서:

1. 예약 상태 조건부 검증
2. EXPIRED 또는 RELEASED로 전환
3. reserved -1, available +1
4. Order CANCELLED
5. 구매 슬롯 해제
6. Commit

CONFIRMING, RECONCILING 상태의 거래는 일반 만료 작업이 반환하지 않는다.

## TX5. 전액 환불 확정

PG 취소가 확인된 뒤 하나의 트랜잭션에서:

1. 환불 결과 기록
2. Order REFUNDED
3. Reservation RESTORED
4. sold -1, available +1
5. Commit

PG 취소 결과가 불명확하면 재고를 복원하지 않는다.
