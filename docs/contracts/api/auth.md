# 인증·회원 API

> 담당: 홍승연
>
> 상태: IMPLEMENTED (코드 기준)
>
> 관련 코드: `backend/src/main/java/com/restless/auth/`, `backend/src/main/java/com/restless/member/`
>
> 마지막 확인: 2026-11-20 (#290 요청 횟수 제한 한 줄을 `application.yml`과 대조해 추가. 2026-11-16 엔드포인트, 공개 경로를 `SecurityConfig`와 대조. `common.md`에서 분리)

오류 응답 형식은 [README 공통 규칙 3](README.md#공통-규칙)을 따른다.

## 엔드포인트

| Method | Endpoint | 기능 | 구현 |
|---|---|---|---|
| POST | `/api/auth/signup` | 회원가입 `{email, password, nickname}` → 201 `{memberId}` | 구현 |
| POST | `/api/auth/login` | 로그인 → 200 `{accessToken, tokenType, expiresIn}` + Refresh 쿠키 | 구현 |
| POST | `/api/auth/refresh` | Refresh 쿠키로 새 Access, 새 Refresh 쿠키 발급 | 구현 |
| POST | `/api/auth/logout` | 이 기기의 Refresh 폐기, 쿠키 삭제 → 204 | 구현 |
| GET | `/api/me/profile` | 내 프로필, 기본 배송지 → 200 프로필 | 구현 |
| PATCH | `/api/me/profile` | 닉네임, 기본 배송지 수정 → 200 바뀐 프로필 | 구현 |
| POST | `/api/me/withdraw` | 탈퇴 `{password, paidOrderNoticeAgreed}` → 204 | 구현 |

로그인, 가입, 갱신은 요청 횟수를 제한한다. 한도를 넘으면 429 `TOO_MANY_REQUESTS`와 `Retry-After`를 준다 ([한도](README.md#요청-횟수-제한)).

### 프로필

응답 (조회, 수정 공통). 기본 배송지를 등록하지 않았으면 null이다.

```json
{
  "memberId": 1,
  "email": "user@example.com",
  "nickname": "철수",
  "role": "USER",
  "defaultRecipient": "김철수",
  "defaultPhone": "010-1234-5678",
  "defaultAddress": "서울시 강남구 1"
}
```

수정 요청은 보낸 필드만 바꾼다.

| 필드 | 규칙 |
|---|---|
| `nickname` | 2~30자, 공백만은 안 됨. 지울 수 없음 |
| `defaultRecipient` | 50자까지 |
| `defaultPhone` | 20자까지, 숫자와 `-`만 |
| `defaultAddress` | 255자까지 |

- 필드를 빼거나 null이면 그대로 둔다. 기본 배송지 필드에 `""`를 보내면 그 값을 지운다.
- 이메일, 역할은 바꿀 수 없다(보내도 무시).
- 탈퇴한 회원은 Access Token이 남아 있어도 404 `MEMBER_NOT_FOUND`. 문구는 `탈퇴한 회원입니다.`다. 없는 회원은 기존 문구 그대로다 (#110).
- 드롭 주문의 "기본 배송지 불러오기"는 이 조회 API를 쓴다.

### 탈퇴

- 비밀번호를 다시 받는다. 틀리면 400 `MEMBER_PASSWORD_MISMATCH`.
- 결제 완료 주문 안내에 동의를 받는다 (#97). `paidOrderNoticeAgreed`가 `true`가 아니면 400 `INVALID_REQUEST`이고 `details.fields.paidOrderNoticeAgreed`가 붙는다. 필드를 빼도 같다.
- 동의를 받는 이유: 결제 완료 주문은 탈퇴를 막지 않는다. 탈퇴하면 그 주문을 조회하거나 환불 요청할 수 없다. 프론트는 탈퇴 화면에 아래 안내와 체크 상자를 둔다.

  > 탈퇴하면 결제 완료 주문을 더 이상 조회하거나 환불 요청할 수 없습니다. 이 내용을 확인했습니다.

- 행은 지우지 않는다(`status = WITHDRAWN`, `deleted_at`). 주문, 결제, 응모 같은 다른 도메인 기록은 그대로 남는다.
- 모든 기기의 Refresh Token을 폐기한다. 다시 로그인할 수 없고, 같은 이메일로 다시 가입할 수도 없다(`uk_users_email`).
- Access Token은 만료(최대 1시간)까지 남는다. 그 토큰으로 보낸 쓰기 요청(POST, PUT, PATCH, DELETE)은 모든 도메인에서 404 `MEMBER_NOT_FOUND`(`탈퇴한 회원입니다.`)로 막는다. 가입, 로그인, 갱신, 로그아웃은 막지 않는다. 역할이 모자라면 403이 먼저다 (`WithdrawnMemberFilter`). `restless.auth.withdrawn-block.enabled=false`면 끈다. 기본은 켜짐이고 perf 프로필에서만 끈다(`WITHDRAWN_BLOCK_ENABLED=true`로 켤 수 있다).
- 조회 요청은 막지 않는다. 회원 API는 404를 주지만 다른 도메인 조회는 통과할 수 있다. 탈퇴 확인 직후 탈퇴하는 동시 요청도 통과할 수 있으니, 결제처럼 중요한 처리는 서비스에서 `memberService.getActive(userId)`로 한 번 더 확인한다.
- **진행 중인 거래가 있으면 막는다 (`WithdrawalGuard`).** 도메인은 `member.application.WithdrawalGuard`를 구현한 `@Component`를 자기 패키지에 두면 된다. 하나라도 `true`를 돌려주면 409 `MEMBER_HAS_ACTIVE_PARTICIPATION`. 지금 등록된 Guard는 팬미팅(`FanMeetingWithdrawalGuard`)과 드롭(`DropWithdrawalGuard`)이다.

```java
@Component
class FanMeetingWithdrawalGuard implements WithdrawalGuard {
    @Override
    public boolean hasActiveParticipation(Long userId) {
        // 이 도메인에서 탈퇴를 막아야 할 진행 중인 거래가 있으면 true
    }
}
```

- 탈퇴가 끝나면 `MemberWithdrawnEvent(userId)`가 같은 트랜잭션에서 발행된다. 인증은 이 이벤트로 Refresh Token을 폐기한다.

## 인증 규칙

- **Access Token**: JWT(HS256), 1시간. 로그인 응답 본문으로 주고, 요청 때 `Authorization: Bearer <accessToken>` 헤더로 보낸다.
- **Refresh Token**: 7일. `refresh_token` HttpOnly 쿠키(`Secure`, `SameSite=Strict`, `Path=/api/auth`)로만 오간다. 프론트는 fetch에 `credentials: 'include'`를 준다. 재발급하면 새 토큰으로 바뀌고, 이미 쓴 토큰이 다시 오면 그 로그인의 토큰을 모두 폐기한다.
- **로그인 필요**: 아래 공개 경로를 뺀 모든 API. 토큰이 없거나 만료·위조면 401 `UNAUTHORIZED`.
- **공개 경로** (`SecurityConfig`): 아래 경로는 로그인 없이 부를 수 있다. 로그인 없이 볼 조회 API를 만들면 `SecurityConfig.PUBLIC_GET_PATHS`에 추가한다.
  - POST: `/api/auth/signup`, `/login`, `/refresh`, `/logout`
  - GET: `/api/concerts`, `/api/concerts/**`(공연 상세, 회차 목록), `/api/drops`, `/api/drops/{dropId}`, `/api/auctions`, `/api/auctions/{auctionId}`, `/api/auctions/{auctionId}/result`, `/api/fan-meetings/{eventId}`, `/actuator/health`
  - 회차 좌석(`/api/performances/{performanceId}/seats`)과 경매 실시간 스트림(`/api/auctions/{auctionId}/stream`)은 공개 경로가 아니다. 로그인이 필요하다.
- **관리자**: `/api/admin/**`는 `role = ADMIN`만. 아니면 403 `FORBIDDEN`. 관리자 기능은 이 경로 아래로 만든다(컨트롤러에서 권한을 다시 검사하지 않는다). 가입하면 USER이고, ADMIN은 DB에서 지정한다.
- **경매 등록·수정·삭제**: `POST /api/auctions`, `PATCH`·`DELETE /api/auctions/{auctionId}`는 `role = ORGANIZER` 또는 `ADMIN`만. 본인 경매인지는 경매 서비스가 확인한다 ([경매 API](auction.md)).
- **주최자**: `/api/organizer/**`는 `role = ORGANIZER` 또는 `ADMIN`만. 아니면 403 `FORBIDDEN`. 주최자 기능은 이 경로 아래로 만든다. 보안 설정은 역할만 보므로, 본인 것인지(예: 자기 행사)는 각 도메인 서비스가 확인한다. ORGANIZER도 DB에서 지정한다.
- **로그인 회원 ID**: 컨트롤러 파라미터에 `@LoginMember Long userId`를 쓴다. 요청 본문으로 회원 ID를 받지 않는다.
- **현재 시각**: `Instant.now()` 대신 `Clock`을 주입받아 `clock.instant()`로 읽는다(`common/config/ClockConfig`). 테스트에서 시각을 바꿀 수 있다.

## 주최자·관리자 권한 관계 정책 (DECIDED, 2026-11-24)

ADMIN은 주최자 기능을 수행할 수 있다. 티켓팅 MVP에서 ORGANIZER는 본인 공연·회차, ADMIN은 모든 공연·회차를 관리하되 동일한 공개 후 수정·삭제 제한과 사업 규칙을 준수한다. 티켓팅 주최자 서비스는 이 권한을 구현했다.

공개 이력이 생긴 뒤에는 제목·소개 수정, 예매 오픈 전 예매 오픈 시각 변경, 회차 추가, 비공개 전환·재공개, 주최 측 취소와 환불 완료 뒤 삭제만 할 수 있다(2026-11-26 개정). 관리자 예외 수정·삭제는 없고 운영 정지·재개는 정책에서 뺐다. 다른 도메인의 기존 권한 정책은 이번 티켓팅 범위 축소로 변경하지 않는다. 정본은 [티켓팅 주최자 정책](../../domains/ticketing/organizer-policy.md)이다.
