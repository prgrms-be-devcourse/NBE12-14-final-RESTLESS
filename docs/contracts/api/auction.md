# 경매 API (취합자 초안)

- 담당: 천종원
- 상태: Draft. 원본 문서에 API가 없어 [design.md](../../domains/auction/design.md)의 기능 표를 보고 이태호가 작성했다. 전부 담당자 확인이 필요하다.

## 사용자

| Method | Endpoint | 기능 | 원본 기능 |
|---|---|---|---|
| GET | `/api/auctions?status=` | 경매 목록 (진행 중, 예정, 종료) | 경매 목록 조회 |
| GET | `/api/auctions/{auctionId}` | 상세: 현재 최고가, 최소 입찰 가능 금액, 종료 시각, 내 입찰 상태 | 경매 상세, 재접속 |
| GET | `/api/auctions/{auctionId}/bids` | 입찰 내역 | 실시간 갱신 |
| POST | `/api/auctions/{auctionId}/bids` | 입찰 (`Idempotency-Key`) | 입찰 |
| GET | `/api/auctions/{auctionId}/result` | 낙찰 결과 | 결과 조회 |
| GET | `/api/me/bids` | 내가 참여한 경매, 최고 입찰 여부, 낙찰 결과 | 내 입찰 조회 |
| GET | `/api/me/wallet` | 보유, 묶인, 사용 가능 크레딧 | 크레딧 |
| POST | `/api/me/wallet/grants` | 크레딧 얻기 버튼 | 초기 크레딧 지급 |

## 판매자

| Method | Endpoint | 기능 |
|---|---|---|
| POST | `/api/seller/auctions` | 경매 등록 (물품, 시작가, 최소 인상액, 일정, 입장 조건) |
| PATCH | `/api/seller/auctions/{auctionId}` | 시작 전 수정 (범위 미정) |
| POST | `/api/seller/auctions/{auctionId}/cancel` | 취소 (범위 미정) |

판매자 권한 이름과 경로(`/seller` 또는 `/admin`)는 회원 역할이 정해지면 맞춘다.

## 실시간 전달 (OPEN)

최고가, 입찰 내역, 종료 시각, 종료 결과를 관전자에게 보낸다. WebSocket과 SSE 중 무엇을 쓸지 미정이다. 후보:

- WebSocket: `/ws/auctions/{auctionId}`
- SSE: `GET /api/auctions/{auctionId}/stream`

## 입찰

```http
POST /api/auctions/{auctionId}/bids
Idempotency-Key: 3b1c...
Content-Type: application/json

{ "amount": 60000 }
```

성공:

```json
{
  "bidId": 77,
  "auctionId": 12,
  "amount": 60000,
  "status": "HIGHEST",
  "endsAt": "2026-11-20T10:30:00Z"
}
```

## 오류 코드 후보

| 코드 | 의미 |
|---|---|
| `AUCTION_NOT_OPEN` | 시작 전 |
| `AUCTION_CLOSED` | 종료됨 |
| `AUCTION_ENTRY_NOT_ALLOWED` | 입장 자격 없음 (티켓 미보유) |
| `BID_AMOUNT_TOO_LOW` | 시작가 또는 최고가 + 최소 인상액 미만 |
| `BID_BY_SELLER` | 판매자 본인 입찰 |
| `BID_ALREADY_HIGHEST` | 이미 최고 입찰자 |
| `CREDIT_INSUFFICIENT` | 사용 가능 크레딧 부족 |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 요청 |
