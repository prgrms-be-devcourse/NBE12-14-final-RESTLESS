# 쿠폰 API

- 담당: 홍승연
- 출처: [design.md 8.3](../../domains/coupon/design.md). 경로를 `/api/v1`에서 `/api`로 바꾼 것 외에는 원본과 같다.
- 상태: Draft

| Method | Endpoint | 기능 | 권한 |
|---|---|---|---|
| GET | `/api/coupon-campaigns` | 행사 목록 | 공개 |
| GET | `/api/coupon-campaigns/{campaignId}` | 행사 및 내 발급 상태 | 공개 |
| POST | `/api/coupon-campaigns/{campaignId}/issuances` | 쿠폰 발급 | 회원 |
| GET | `/api/me/coupons` | 내 쿠폰 | 회원 |
| POST | `/api/admin/coupon-campaigns` | 행사 등록 | ADMIN |
| GET | `/api/admin/coupon-campaigns/{campaignId}` | 한도, 발급 수, 상태 | ADMIN |
| PATCH | `/api/admin/coupon-campaigns/{campaignId}` | 시작 전 수정 | ADMIN |
| POST | `/api/admin/coupon-campaigns/{campaignId}/cancel` | 시작 전 취소 | ADMIN |

재시도는 `UNIQUE(campaign_id, user_id)`로 막으므로 `Idempotency-Key`를 쓰지 않는다.

## 발급 성공 (201)

```json
{
  "issuanceId": 1,
  "campaignId": 10,
  "status": "ISSUED",
  "issuedAt": "2026-11-20T11:00:00Z"
}
```

ID는 원본의 UUID에서 숫자로 바뀌었다 ([ADR-0002](../../decisions/0002-common-conventions.md)).

## 오류 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `COUPON_ALREADY_ISSUED` | 409 | 이미 받음 |
| `COUPON_SOLD_OUT` | 409 | 수량 소진 |
| `COUPON_NOT_OPEN` | 422 | 시작 전 |
| `COUPON_CLOSED` | 422 | 기간 종료 |
