# 로컬 티켓팅 샘플

기존 예매·결제 API를 수동으로 확인할 개발 데이터다. 운영자 생성 API나 부하 테스트 시드가 아니며, 자동 실행되는 Flyway 경로에 넣지 않는다.

## 준비와 실행

1. `backend/`에서 `PG_PROVIDER=fake ./gradlew bootRun`으로 앱을 실행한다. Java 21과 Docker가 필요하며, 앱 시작 시 Flyway가 현재 스키마를 적용한다. 기존 DB의 마이그레이션 오류는 해결한 뒤 진행한다.
2. 저장소 루트에서 `bash backend/scripts/seed/ticketing/seed.sh`를 실행한다. 저장소 `backend/compose.yaml`의 개발용 MySQL만 사용한다. 별도 `DB_URL`로 앱을 실행했다면 그 DB에는 적재되지 않는다.
3. 출력된 `concert_id`, `performance_id`, `seat_id`를 사용한다. ID를 고정하거나 추측하지 않는다.

기존 공연·회원·예약을 지우거나 덮어쓰지 않는다. 한 번 실행할 때 아래 샘플 묶음을 새로 추가한다. 재실행은 같은 샘플 초기화가 아니라 **별도 샘플 생성**이다. 중간 SQL 오류 시 첫 오류에서 종료하고, 커밋 전 데이터는 연결 종료 시 롤백한다. DB 클라이언트의 `--force`로 직접 실행하지 않는다.

| 데이터 | 구성 |
|---|---|
| 주최자 | 로그인 불가능한 해시를 가진 시드 전용 ORGANIZER 1명. 기존 회원 권한은 변경하지 않음 |
| 공연장·배치 | 올림픽홀을 참고한 반원형 가상 공연장 1개, A/B/C 구역, 배치 1개 |
| 물리·배치·판매 좌석 | 구역별 1~5열. A/C는 열당 6석, B는 열당 8석, 총 100석. B구역 5열 1/8번은 접근성 좌석 |
| 가격 | 각 구역 1열 VIP 총 20석 100,000원, 2~5열 R 총 80석 70,000원 |
| 회차 | 1개. 생성 시각부터 7일간 예매 가능, 8일 뒤 공연, 회원당 최대 4석 |
| 최초 상태 | 전 좌석 AVAILABLE/current_hold_id=NULL. 예약·선점·주문·결제·티켓은 없음 |

시각은 DB UTC 기준이며 표시 시간대는 Asia/Seoul이다. 관리 상태 문자열은 기존 매핑 예시 값을 사용하며 새 공개 상태 정책을 정의하지 않는다. 만료되거나 이미 예매한 샘플을 재설정하지 말고 새 묶음을 만든다.

## 프론트 없이 확인하는 순서

Postman 또는 HTTP 클라이언트로 아래 순서대로 호출한다. 구매자는 기존 계정으로 로그인하거나 `POST /api/auth/signup`으로 가입한 뒤 `POST /api/auth/login`에서 accessToken을 받는다. 시드 주최자는 로그인용 계정이 아니다. 비밀번호·토큰을 Git에 저장하지 않는다.

1. `GET /api/concerts/{concertId}/performances`로 회차를 확인한다.
2. `GET /api/performances/{performanceId}/seats`에 Bearer 토큰을 붙여 좌석을 확인한다.
3. `POST /api/ticket-reservations`에 Bearer 토큰, 새 `Idempotency-Key`, 아래 본문을 보낸다. `seatIds`는 판매 좌석 ID이며 물리 좌석 ID가 아니다.

   ```json
   { "performanceId": 123, "seatIds": [456, 457] }
   ```

4. 선점 후 5분 이내에 `POST /api/ticket-reservations/{reservationId}/checkout`을 호출한다. 응답의 공통 `orderId`와 최초 `paymentExpiresAt`을 확인한다.
5. `POST /api/payments`에 Bearer 토큰, 새 `Idempotency-Key`, `{"payableType":"TICKET","payableId":예약ID}`를 보낸다.
6. **PG_PROVIDER=fake로 실행한 로컬 앱에서만** `POST /api/payments/confirm`에 아래 값을 보낸다. 기본 Fake PG는 승인 성공을 반환한다. 별도 실패율 설정이 있으면 그 설정을 따른다.

   ```json
   { "paymentKey": "local-fake-매번새로운값", "orderId": "결제준비응답의 pgOrderId", "amount": 200000 }
   ```

   `amount`는 예시를 복사하지 말고 준비 응답의 금액을 사용한다. 여기서 `orderId`는 checkout의 숫자 주문 ID가 아닌 **준비 응답의 pgOrderId**다. `paymentKey` 임의 생성은 Fake PG에서만 가능하다. 토스 테스트 환경에서는 결제창 인증으로 받은 값을 사용해야 한다.

7. `GET /api/orders/{공통orderId}`에서 PAID·APPROVED와 예약 연결을, `GET /api/tickets`에서 본인 발급 티켓을 확인한다. Bearer 토큰이 필요하다.

같은 키·내용으로 선점 요청을 재전송하면 같은 예약과 최초 기한이 유지된다. 다른 구매자로 같은 좌석을 선점하면 거절된다. 이 시드는 좌석 FK·공연장·회차·배치 관계를 그대로 지키며, 외래 키 검사나 업무 제약을 끄지 않는다.

## 반원형 배치 미리보기

프론트는 **`frontend-test/`에서만** 수정하고 실행한다. `frontend/`는 사용하지 않는다. `frontend-test/`에서 `npm run dev` 후 표시된 주소(기본 http://localhost:3000)의 `/performances/{performanceId}?max=4&concert={concertId}`를 연다. 로그인 후 기존 백엔드 API가 반환한 좌석 좌표·구역·열·번호·가격으로 배치를 표시한다. 백엔드 기본 주소는 `next.config.ts`의 http://localhost:8080이다.

DB 시드는 `backend/scripts/seed/ticketing/layout.json`을 사용하고, 프론트는 DB를 조회하는 API 응답을 사용하므로 별도 하드코딩 좌석 목록을 두지 않는다. 구역별 경계는 API 좌표를 감싸는 외곽선이며 공식 도면의 통로 경계가 아니다. 하단 구역 목록을 클릭하면 해당 구역을 강조·확대하고, 전체 보기로 돌아갈 수 있다. 구역 이동 시 선택 좌석을 유지하며 기존 선점·checkout·결제 흐름을 사용한다. 기존 FANQ 테마 토큰·그라데이션·카드·장바구니를 유지한다.

좌석 클릭만으로 DB 선점이 되지는 않는다. ‘이 좌석 잡기!’가 기존 선점 API를 호출한다. 잔여석은 API로 주기 갱신하며 선점 트랜잭션에서 최종 확인한다. 공식 좌석배치·구역명·좌석번호·시야와 구분해 안내한다. 구역은 위치(A/B/C), VIP/R은 가격 등급이며 서로 다른 개념이다.

반원형 변경 전 생성한 사각형 샘플·기존 예매는 그대로 보존한다. 새 배치는 `bash backend/scripts/seed/ticketing/seed.sh`로 별도 생성하고 출력된 새 ID를 사용한다. 기존 회차의 배치나 좌석을 덮어쓰지 않는다. 공식 참고: https://www.ksponco.or.kr/menu.es?mid=a20301031000
