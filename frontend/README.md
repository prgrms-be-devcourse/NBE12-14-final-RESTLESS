# FANQ 웹 (restless-web)

RESTLESS 백엔드(Spring Boot)에 연결되는 Next.js(App Router) 프론트엔드.

## 실행

```bash
npm install
npm run dev          # http://localhost:3000
```

- `/api/*` 요청은 `next.config.ts`의 rewrites로 백엔드에 전달된다. 기본값 `http://localhost:8080`, 바꾸려면 `BACKEND_URL` 환경 변수(`.env.example` 참고).
- API 조회에 실패하면 오류 상태를 표시하며 임시 데이터로 대체하지 않는다. 로그인과 회원가입도 백엔드 연결이 필요하다.

## 화면

빌드 결과는 S3에 올리는 정적 파일(`output: "export"`)이라 동적 경로를 쓰지 않는다. 상세 화면 id는 쿼리 문자열로 넘긴다.

| 경로 | 내용 | API |
|---|---|---|
| `/` | 워프 인트로(길게 눌러 워프) → 스플릿 메뉴 | - |
| `/home` | 문구 띠, 마감 임박 경매, 드롭·공연·경매, 팬미팅 준비 중 안내 | concerts, drops, auctions |
| `/concerts`, `/concerts/detail?id=` | 공연 목록, 회차 선택 | `GET /api/concerts`, `/{id}/performances` |
| `/performances?id=` | 좌석 배치도 → 선점 → 체크아웃 | `GET /seats`, `GET /seats/status` (수동 새로고침), `POST /api/ticket-reservations`, `/checkout`, `DELETE` |
| `/drops`, `/drops/detail?id=` | 옵션, 배송지 확인 → 결제하기(대기열 입장) → 입장 허가 → 자동 재고 점유와 주문 생성 → 토스 결제창 | `POST /queue/entries`, `GET /queue/me`, `POST /reservations` |
| `/auctions`, `/auctions/detail?id=` | 실시간 입찰(SSE `event: auction`) | `GET /stream`, `POST /bids`, `GET /api/me/wallet` |
| `/fan-meetings`, `/fan-meetings/detail?id=` | 목록은 API 준비 중 안내, 상세 응모·취소 | `GET /{id}`, `POST/GET/DELETE /applications` |
| `/fan-meetings/result?id=` | 추첨 결과: 당첨·예비 순번·미당첨, 결제 포기 | `/result/me`, `/orders/me`, `/orders/{orderId}/decline` |
| `/checkout?order=` | 공통 결제(금액 고정) → 완료·실패·확인 중 | `GET /api/orders/{id}`, `POST /api/payments`, `/confirm` |
| `/me` | 프로필·배송지 수정, 크레딧, 진행 중 예매, 주문 요약, 회원 탈퇴 | `/api/me/profile`, `/wallet`, `/wallet/grants`, `POST /api/me/withdraw` |
| `/me/orders` | 주문·참여 내역 6상태(결제 대기·확인 중·완료·만료·포기·환불됨) | `GET /api/me/orders`* |
| `/admin` | 역할별 드롭 등록·공개 및 팬미팅 등록 | `/api/admin/drops`, `/api/organizer/fan-meetings` |
| `/login`, `/signup` | 인증 | `/api/auth/*` |

팬미팅 공개 목록과 쿠폰은 백엔드 API가 없어 안내/비노출 상태다.

## 결제 (토스 결제창형)

흐름: `POST /api/payments`(준비) → 토스 결제창(`src/lib/toss.ts`) → `/checkout/success?order=`가 `POST /api/payments/confirm` → 결제 화면이 서버 상태를 보여 줌. 실패와 취소는 `/checkout/fail?order=`. 토스가 복귀 주소에 `orderId`(PG 주문번호)를 붙이므로 우리 주문 id는 `order`로 넘긴다.

- 프론트: `.env.local`에 `NEXT_PUBLIC_TOSS_CLIENT_KEY`(클라이언트 키만). 기본값은 문서용 공개 테스트 키 `test_gck_docs_*`다.
- 백엔드: 같은 종류의 시크릿 키가 짝이어야 한다. 예) `PG_PROVIDER=toss TOSS_SECRET_KEY=test_gsk_docs_... ./gradlew bootRun`. 시크릿 키는 서버에서만 읽는다.
- `PG_PROVIDER`를 주지 않으면 백엔드는 가짜 PG로 돌아가고, 결제창을 지나면 어떤 paymentKey든 승인된다.

## 구조

- `src/lib/api.ts` — fetch 래퍼. Bearer 토큰, `credentials: 'include'`(Refresh 쿠키), 401 시 `/api/auth/refresh` 한 번 재시도, `Idempotency-Key` 지원
- `src/lib/types.ts` — 백엔드 DTO 타입
- `src/components/` — Starfield(#330), SplitMenu(#312), Marquee(#394), SideRail(#040), Poster, Cards, Tabs
- 테마: `src/app/globals.css`의 `--ux-*` 토큰. 기본은 **우주(다크)**, 레일의 테마 버튼으로 **새벽(라이트)** 으로 바꿀 수 있다(`data-theme`).
- 본 화면(`(main)` 레이아웃) 뒤에도 인트로와 같은 별빛(Starfield)이 흐른다. 들어올 때 워프에서 순항으로 감속하고, 스크롤하면 빨라진다. 카드·레일·문구 띠는 별이 비치는 유리판이다.
