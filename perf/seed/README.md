# 부하 테스트 시드

Flyway로 만든 빈 스키마에 실험 데이터를 SQL로 대량 생성한다. 애플리케이션 코드를 거치지 않는다.
운영 마이그레이션(`backend/src/main/resources/db/migration`)과 분리되어 있어 운영 DB에는 들어가지 않는다.

## 구현 범위

| 도메인 | 상태 |
|---|---|
| member (users, refresh_tokens, credit_wallets) | 구현 |
| drop | 구현 |
| order, payment | 구현. 드롭 배경 데이터에서 파생 |
| ticketing | 드롭이 참조하는 공연장 1개, 공연만. 좌석, 회차, 선점은 담당자 구현 예정 |
| fanmeeting, auction | 담당자 구현 예정 (`30_fanmeeting.sql`, `40_auction.sql`) |
| coupon | 테이블이 없어 제외 |

## 실행

준비: 대상 DB에 앱을 `perf` 프로필로 한 번 띄워 Flyway 마이그레이션을 끝낸다. 실행 머신에는 `mysql` 클라이언트와 `python3`(표준 라이브러리만)이 필요하다.

```bash
export DB_HOST=10.0.1.20 DB_NAME=restless DB_USERNAME=restless DB_PASSWORD=...
export JWT_SECRET=...                      # 앱의 perf 프로필과 같은 값
export PERF_ALLOWED_DB_HOSTS=10.0.1.20     # 쉼표로 여러 개
perf/seed/seed.sh --scale base --profile perf
```

| 모드 | 하는 일 |
|---|---|
| 기본 | 시드, `out/` 토큰 CSV, 점검. 빈 스키마에서만 실행된다 |
| `--refresh` | 스냅샷 복원 뒤 실험 전에 실행. 핫 드롭 판매 기간과 Refresh Token 만료를 지금 기준으로 다시 맞추고 CSV를 다시 만든다 |
| `--verify` | 점검만 |

로컬에서는 `--scale small`을 쓰고, mysql 클라이언트가 없으면 `MYSQL="docker exec -i -e MYSQL_PWD <컨테이너> mysql"`로 컨테이너 안의 클라이언트를 쓴다.

## 안전장치

다음 중 하나라도 어기면 아무것도 쓰지 않고 멈춘다.

1. `--profile perf`가 아니다. 앱의 `application-perf.yml`과 같은 이름이다.
2. `DB_HOST`가 `PERF_ALLOWED_DB_HOSTS`에 정확히 없다.
3. 호스트나 DB 이름에 prod가 들어 있다.
4. 시드 모드인데 `users`에 행이 있거나 이미 시드한 DB다. 다른 모드인데 `perf_seed_meta`가 없거나 시드한 스케일이 다르다.

토큰은 시드가 직접 서명하므로 만료(`token_ttl_hours`)를 테스트 길이보다 길게 둘 수 있다. perf 환경의 `JWT_SECRET`으로만 서명하므로 다른 환경에서는 통하지 않는다. 앱의 Access Token TTL 설정은 바꾸지 않는다.

## 수치

모든 수치는 [scale.yml](scale.yml)에서 바꾼다. small은 로컬, base는 기본 실험, large는 확장 실험용이다.

| 구분 | base |
|---|---|
| 회원 | users 100,000 + 관리자 1 + 주최자 1, refresh_tokens와 credit_wallets 회원마다 1 |
| 핫 드롭 | 1건, SKU 3, 총재고 10,000, 경쟁률 10:1 → 참여자 100,000 |
| 배경 드롭 | 종료 100건, SKU 300, 점유 100,000 (CONFIRMED 80%, RESTORED 3%, RELEASED 7%, EXPIRED 10%) |
| 주문, 결제 | 배경 점유마다 주문 1, 품목 1, 배송 스냅샷 1, 결제 1. 환불 완료 3,000 |

핫 데이터는 비어 있는 상태로 시작한다. 점유, 주문, 결제, 대기열 등록은 테스트가 만든다. 대기열을 미리 채워야 하면 `drop_hot_queue_prefill`을 쓴다.
핫 기준값(총재고)은 `perf_seed_meta`와 `out/manifest.json`에 남는다. 테스트 뒤 불변식 대조에 쓴다.

## ID 규칙

ID를 명시적으로 넣어서 시나리오가 값을 예측할 수 있다. 앱이 새로 만드는 행은 시드 ID 뒤부터 이어진다.

| 테이블 | ID |
|---|---|
| users | 회원 1..users, 관리자 users+1, 주최자 users+2 |
| drops | 핫 1, 배경 2.. |
| drop_variants | 핫 1..3, 배경 4.. |
| drop_reservations, orders, payments | 배경 점유 n에 대해 모두 n |

## 출력 (`out/`, git에 올리지 않음)

| 파일 | 내용 |
|---|---|
| users.csv | user_id, email, role, access_token, refresh_token. 핫 참여자는 앞에서부터 participants 행 |
| staff.csv | 관리자, 주최자 |
| manifest.json | 핫 드롭 ID, SKU별 재고, 판매 기간, 경쟁률 |

모든 계정의 비밀번호는 `perf-password`다. 로그인 자체를 측정할 때만 쓰고, 나머지는 CSV 토큰을 쓴다.
Refresh Token은 한 번 쓰면 교체되므로 같은 행을 다시 쓰려면 스냅샷을 복원한다.

## 점검

시드 마지막에 [verify.sql](verify.sql)과 드롭 불변식 SQL(`backend/src/test/resources/harness/drop-invariants.sql`)을 실행한다. 위반이 1건이라도 있으면 실패한다.

- 스키마의 모든 FK 고아 행. 시드는 속도를 위해 FK 검사를 끄고 넣으므로 여기서 확인한다.
- 주문 금액과 품목 합계, 결제 금액, 점유·주문·결제 상태 조합, 환불 금액
- 드롭 불변식 INV-01~09
- 시드 기준값: 행 수, 핫 재고 합계, 핫 드롭이 비어 있는지

부하 테스트 뒤 `--verify`를 실행하면 시드 기준값 검사는 실패하는 것이 정상이다. 그때는 불변식과 금액 검사가 0인지 본다.

## 스냅샷과 복원

1. 앱을 perf 프로필로 띄워 마이그레이션한 뒤 앱을 멈춘다.
2. `seed.sh --scale base --profile perf`
3. 스냅샷을 만든다. RDS는 DB 스냅샷, EC2 직접 설치는 mysqld를 멈추고 데이터 디렉터리를 복사하거나 EBS 스냅샷을 쓴다.
4. 실험마다 복원 → `seed.sh --refresh` → 앱 기동 → 측정
5. RDS 스냅샷 복원 직후에는 데이터를 S3에서 지연 로딩하므로 첫 쿼리가 느리다. 측정 전에 주요 테이블을 한 번씩 전체 조회해 데우고, 데운 방법을 결과에 적는다.

## 측정한 시드 시간

| 환경 | 스케일 | 시간 |
|---|---|---|
| 로컬 macOS, Docker MySQL 8.4 | base | 약 10초 (토큰과 점검 포함) |

EC2와 RDS에서는 아직 재지 않았다.
