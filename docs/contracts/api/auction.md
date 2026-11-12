# 경매 API

- 담당: 천종원
- 상태: PROPOSED (1차 구현 기준). 정책은 [policy.md](../../domains/auction/policy.md)를 따른다.
- 오류 응답 형식은 [공통 규칙](README.md)을 따른다.

## 조회 (로그인 불필요)

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/auctions?status=&page=&size=` | 목록. `status`는 `SCHEDULED`, `OPEN`, `CLOSED` |
| GET | `/api/auctions/{auctionId}` | 상세: 물품, 현재 최고 입찰, 최소 입찰 가능 금액, 종료 시각, `revision` |
| GET | `/api/auctions/{auctionId}/stream` | 실시간 상태 (SSE) |
| GET | `/api/auctions/{auctionId}/result` | 종료 결과. 종료 전이면 404 `AUCTION_RESULT_NOT_FOUND` |

입찰 전체 내역 API는 1차에서 제공하지 않는다. 입찰은 모두 저장하지만 화면에는 최고 입찰 하나만 보여 준다.

## 회원

| Method | Endpoint | 기능 |
|---|---|---|
| POST | `/api/auctions/{auctionId}/bids` | 입찰. `Idempotency-Key` 헤더 필수 |
| GET | `/api/me/bids?status=` | 내가 참여한 경매별 마지막 입찰과 상태(`HIGHEST`, `OUTBID`, `WON`, `LOST`) |
| GET | `/api/me/wallet` | 보유, 묶인, 사용 가능 크레딧 |
| POST | `/api/me/wallet/grants` | 테스트용 10,000 지급. 운영(prod)에서는 없음 |

`LOST`는 종료된 경매에서 내 마지막 입찰이 낙찰되지 않은 경우다.

## 주최자 (ORGANIZER, ADMIN)

| Method | Endpoint | 기능 | 조건 |
|---|---|---|---|
| POST | `/api/auctions` | 등록 | |
| PATCH | `/api/auctions/{auctionId}` | 수정 | 본인 경매(ADMIN 예외), 시작 전 |
| DELETE | `/api/auctions/{auctionId}` | 삭제 | 본인 경매(ADMIN 예외), 시작 전 |

USER가 이 API를 직접 호출하면 Security가 403 `FORBIDDEN`으로 막는다.

### 등록

```http
POST /api/auctions
Content-Type: application/json

{
  "title": "투어 사인 포스터 경매",
  "itemName": "사인 포스터",
  "itemDescription": "2026 투어 한정 친필 사인",
  "startingPriceAmount": 10000,
  "minimumIncrementAmount": 1000,
  "startsAt": "2026-11-20T10:00:00Z",
  "endsAt": "2026-11-20T11:00:00Z",
  "extensionDurationSeconds": 60
}
```

- `extensionDurationSeconds`는 생략하면 60이고, 30 ~ 300만 받는다.
- 응답: 201 `{ "auctionId": 12 }`
- 수정(PATCH)은 같은 필드를 받는다.

## 상세

```json
{
  "auctionId": 12,
  "title": "투어 사인 포스터 경매",
  "itemName": "사인 포스터",
  "itemDescription": "2026 투어 한정 친필 사인",
  "sellerNickname": "주최사",
  "status": "OPEN",
  "startingPriceAmount": 10000,
  "minimumIncrementAmount": 1000,
  "highestBid": { "nickname": "팬123", "amount": 52000, "acceptedAt": "2026-11-20T10:29:59.120Z" },
  "minimumBidAmount": 53000,
  "startsAt": "2026-11-20T10:00:00Z",
  "endsAt": "2026-11-20T11:00:00Z",
  "extensionDurationSeconds": 60,
  "revision": 15,
  "serverTime": "2026-11-20T10:30:00Z"
}
```

- 입찰이 없으면 `highestBid`는 `null`이고 `minimumBidAmount`는 시작가다.
- `serverTime`은 클라이언트 남은 시간 표시용이다. 입찰 가능 여부는 서버가 판단한다.

## 입찰

```http
POST /api/auctions/12/bids
Idempotency-Key: 3b1c0e7a-...
Content-Type: application/json

{ "amount": 53000 }
```

성공 201:

```json
{
  "bidId": 77,
  "auctionId": 12,
  "amount": 53000,
  "status": "HIGHEST",
  "acceptedAt": "2026-11-20T10:30:00.010Z",
  "endsAt": "2026-11-20T11:00:00Z"
}
```

같은 회원이 같은 `Idempotency-Key`로 다시 보내면 처음 응답과 같은 내용을 돌려준다.

금액 부족 409:

```json
{
  "code": "BID_AMOUNT_TOO_LOW",
  "message": "최소 입찰 가능 금액보다 낮습니다.",
  "details": { "minimumBidAmount": 54000 }
}
```

## 실시간 스트림 (SSE)

```http
GET /api/auctions/12/stream
Accept: text/event-stream
```

서버는 연결 직후 현재 상태를 한 번 보내고, 이후 경매별로 변경을 모아 0.2초마다 최신 상태를 보낸다.

```text
event: auction
id: 15
data: {"auctionId":12,"status":"OPEN","revision":15,
       "highestBid":{"nickname":"팬123","amount":52000,"acceptedAt":"..."},
       "minimumBidAmount":53000,"endsAt":"2026-11-20T11:00:00Z","result":null}
```

- `id`는 `revision`이다. 클라이언트는 받은 `revision`보다 작거나 같은 메시지를 무시한다.
- 종료되면 `status`가 `CLOSED`이고 `result`에 `{ "type": "WON", "winnerNickname": "...", "amount": 60000 }` 또는 `{ "type": "NO_BID" }`가 온다.
- 연결이 끊기면 상세 API로 최신 상태를 받은 뒤 다시 연결한다.
- 이 경로는 `SecurityConfig.PUBLIC_GET_PATHS`에 추가해야 한다 (구현 6단계).

## 오류 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `AUCTION_NOT_FOUND` | 404 | 경매 없음 |
| `AUCTION_RESULT_NOT_FOUND` | 404 | 아직 종료되지 않아 결과 없음 |
| `AUCTION_NOT_OPEN` | 409 | 시작 전 |
| `AUCTION_CLOSED` | 409 | 종료 시각이 지남 |
| `AUCTION_FORBIDDEN` | 403 | 본인 경매가 아님 |
| `AUCTION_ALREADY_STARTED` | 409 | 시작된 경매는 수정·삭제 불가 |
| `BID_AMOUNT_TOO_LOW` | 409 | 최소 입찰 가능 금액 미만. `details.minimumBidAmount` 포함 |
| `BID_BY_SELLER` | 409 | 판매자 본인 입찰 |
| `BID_ALREADY_HIGHEST` | 409 | 이미 현재 최고 입찰자 |
| `CREDIT_INSUFFICIENT` | 409 | 사용 가능 크레딧 부족 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 키로 다른 경매나 금액을 요청 |
| `INVALID_REQUEST` | 400 | 입력 검증 실패 (일정, 연장 시간 범위, 금액 등). 공통 코드 |
