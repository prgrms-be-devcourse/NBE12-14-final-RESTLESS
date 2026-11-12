# Ticketing 도메인

담당자: 송혜민

| 문서 | 내용 |
|---|---|
| [data-dictionary.md](data-dictionary.md) | 공연장·공연·티켓팅 12개 테이블의 모든 컬럼 의미와 예시 |
| [design.md](design.md) | 담당자 설계 원본과 MySQL 정합성 보정 |
| [seat-ownership.md](seat-ownership.md) | 확정된 MySQL 좌석 소유권·잠금·반환 설계 |
| [ticket-issuance.md](ticket-issuance.md) | 확정된 UUID 티켓번호와 중복 발급 방지 |
| [transactions.md](transactions.md) | 결제 준비·PG 호출·결과 확정의 트랜잭션 경계 |
| [expiration.md](expiration.md) | 승인 시도 유무별 만료와 전체 좌석 반환 |
| [idempotency.md](idempotency.md) | MVP 중복 방지와 후속 전용 테이블·실행권 설계 |
| [venue-consistency.md](venue-consistency.md) | 공연장 소속·회차 배치 일치 복합 FK |
| [payment-handler.md](payment-handler.md) | 결제 Handler별 상태 전이와 공통 계약 차이 |
| [reservation-seat-consistency.md](reservation-seat-consistency.md) | 예약·선점·티켓 회차 및 현재 소유 좌석 일치 |
| [purchase-limit.md](purchase-limit.md) | 사용자·회차 관리 행 잠금과 활성 예약·수량 제한 |
| [API 계약](../../contracts/api/ticketing.md) | 원본에서 취합한 API |
| [통합 ERD](../../architecture/erd.md) | 도메인 사이 연결 |

design.md는 Notion 원본을 바탕으로 하며, 합의된 변경은 별도 정본 링크와 함께 반영한다. Notion 첨부 이미지(`attachment:`)는 GitHub에서 보이지 않으므로, 필요한 그림은 mermaid로 바꾸거나 이미지 파일을 이 폴더에 넣는다.

Drop 문서([../drop/](../drop/))처럼 policy, invariants, state-machines, transactions, data-model로 나눌지는 담당자가 정한다.
