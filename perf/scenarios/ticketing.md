# 티켓팅 부하 실험

`ticketing/`의 시나리오 파일들은 좌석 조회와 선점 API를 측정한다. 실제 결제나 checkout은 포함하지 않는다. 결과는 성능 수치가 아닌 측정 도구이며, 테스트 후 DB 정합성 점검까지 해야 한다.

## 시나리오 파일

| 파일 | 시나리오 |
|---|---|
| `ticketing/spread.js` | 좌석 조회, 분산 진입 |
| `ticketing/simultaneous.js` | 좌석 조회, 동시 진입 |
| `ticketing/hot.js` | 인기 좌석 선점 경쟁 |
| `ticketing/overlap.js` | 겹치는 좌석 선점 경쟁 |
| `ticketing/distributed.js` | 서로 다른 좌석 선점 |
| `ticketing/replay.js` | 동일 선점 요청 재전송 |
| `ticketing/mixed.js` | 조회와 인기 좌석 선점 혼합 |
| `ticketing/mixed-distributed.js` | 조회와 서로 다른 좌석 선점 혼합 |

각 파일을 `k6 run`으로 직접 실행하거나 `run-ticketing.sh`에 시나리오 이름을 전달한다. 인증·안전 설정과 지표는 `ticketing/common.js`, 조회와 선점 동작은 `ticketing/poll-logic.js`와 `ticketing/hold-logic.js`가 공유한다.

## 팀원이 준비한 AWS 환경 사용

앱의 perf 프로필·테스트 DB·테스트 시간과 8080/8081 접근은 팀원과 먼저 확인한다. 관리 포트의 Prometheus 응답은 perf 프로필의 증명이 아니다. 기존 데이터를 임의로 변경하거나 아래 시드를 실행하지 않는다. 앱과 관리 포트는 이 프로젝트 안전장치에 따라 `perf-`로 시작하는 허용 호스트를 사용한다. 팀원이 제공하는 실제 DNS 이름을 사용하며, 필요하면 테스트 주소를 `perf-app`으로 연결한다. HTTPS는 인증서의 호스트명과 일치해야 한다.

다음 파일을 팀원의 테스트 계정·Access Token으로 준비한다. 토큰은 테스트 종료까지 유효해야 하며 파일을 Git에 올리거나 공유 채팅에 붙여넣지 않는다. 500명 실험에는 서로 다른 사용자 행이 적어도 500개 필요하다. 기존 perf 시드의 `perf/seed/out/users.csv`도 이 형식이다.

```csv
user_id,access_token
1,첫번째_테스트_액세스_토큰
2,두번째_테스트_액세스_토큰
```

CSV는 쉼표·개행·인용부호 없는 값으로 작성한다. JWT는 앱에서 발급받거나 팀원이 준비한 것을 사용한다. 유효기간·회차 ID·해당 계정의 조회 권한을 확인한다. 토큰 파일을 만들 때는 `chmod 600 <경로>`로 보호한다.

## 짧은 명령으로 실행

설치된 k6와 위에서 준비한 테스트 사용자 CSV가 필요하다. 레포 루트에서 팀원에게 받은 실제 설정을 한 번 지정한다.

```bash
export BASE_URL="http://perf-app:8080"
export MANAGEMENT_URL="http://perf-app:8081"
export PERF_ALLOWED_APP_HOSTS="perf-app"
export PERFORMANCE_ID=1
export USERS_CSV="$PWD/perf/seed/out/users.csv"
```

각 명령의 숫자는 **전체 사용자 수**다. `mixed`와 `mixed-distributed`는 조회 80%, 선점 20%로 나눈다. 실행마다 고유 ID와 `.ai-output/perf/ticketing-*/summary.json`이 자동으로 만들어진다.

```bash
perf/scenarios/run-ticketing.sh spread 100
perf/scenarios/run-ticketing.sh simultaneous 300
perf/scenarios/run-ticketing.sh hot 500
perf/scenarios/run-ticketing.sh overlap 100
perf/scenarios/run-ticketing.sh distributed 300
perf/scenarios/run-ticketing.sh replay 500
perf/scenarios/run-ticketing.sh mixed 100
perf/scenarios/run-ticketing.sh mixed-distributed 300
```

위의 시나리오는 모두 인원을 100, 300, 500 등으로 바꿔 개별 실행한다. 선점·혼합 실행은 좌석 상태를 변경하므로 각 실행 뒤 정합성을 확인하고 다음 실행 전 초기 데이터를 복원하거나 새 회차와 계정을 사용한다. 선점과 혼합 테스트의 관리 방법은 아래 절을 따른다. 조회 기본 2분, 혼합 기본 30초이며 예를 들어 `DURATION=1m perf/scenarios/run-ticketing.sh spread 100`으로 조정한다. 선택 변수는 `perf/scenarios/run-ticketing.sh --help`에서 확인한다. k6 종료 상태는 그대로 반환된다.

## Mac에서 k6 직접 실행

레포 루트에서 실행한다. 아래의 `perf-app` 주소, 포트, `PERFORMANCE_ID`, `USERS_CSV`는 팀원에게 받은 실제 값으로 바꾼다. 설정은 토큰 자체가 아니라 파일 경로만 지정한다.

```bash
mkdir -p .ai-output/perf
export BASE_URL="http://perf-app:8080"
export MANAGEMENT_URL="http://perf-app:8081"
export PERF_ALLOWED_APP_HOSTS="perf-app"
export PERFORMANCE_ID=1
export USERS_CSV="$PWD/perf/seed/out/users.csv"
export PARTICIPANTS=100 VUS=100 DURATION=2m
```

먼저 소수의 사용자로 인증과 회차를 확인한다. 그런 다음 동일한 인원·시간·환경에서 진입 방식만 바꾸어 실행한다.

```bash
# 3초 동안 사용자를 균등하게 분산해 진입
RUN_ID=spread100 \
SUMMARY_PATH="$PWD/.ai-output/perf/ticket-spread100.json" \
k6 run perf/scenarios/ticketing/spread.js

# 사용자를 동시에 진입시킨 뒤 각자 3초 간격으로 조회
RUN_ID=simultaneous100 \
SUMMARY_PATH="$PWD/.ai-output/perf/ticket-simultaneous100.json" \
k6 run perf/scenarios/ticketing/simultaneous.js
```

300명과 500명은 `export PARTICIPANTS=300 VUS=300`처럼 변경하고 `RUN_ID`·`SUMMARY_PATH`도 단계마다 바꾼다. `VUS=PARTICIPANTS`여야 한다. 조회 파일은 파일명으로 진입 방식이 고정된다. 동시 진입은 k6가 모든 VU를 동시에 시작하도록 요청하는 것이며 실제 서버 도착 시각은 네트워크와 부하 생성기 상태에 따라 달라진다. 첫 요청 뒤에도 3초 간격이 비슷해져 주기적으로 요청이 몰릴 수 있다.

한 VU는 하나의 계정으로 첫 조회 이후 요청 시작을 최소 3초 간격으로 반복한다. 응답이 3초를 넘으면 요청률은 낮아진다. 테스트 전 setup에서 관리 포트와 첫 계정의 조회·좌석 수를 확인한다. 좌석이 모두 선점됐더라도 조회 테스트는 가능하다. `ticket_poll_duration`에서 `phase:entry`와 `phase:poll`로 첫 진입과 이후 요청의 지연을 구분한다. setup 요청은 기본 `http_reqs`에 포함되므로 실제 트래픽을 분석할 때 구분한다.

예를 들어 정상적으로 고르게 폴링하면 100명은 약 33회/초, 300명은 약 100회/초, 500명은 약 167회/초다. 목표치가 아닌 계산값이며, 오류와 응답 지연·시작/종료 구간을 함께 고려한다. 서버와 DB 지표, 로컬 k6의 CPU·네트워크 상태와 실제 요청 수를 같이 기록한다. 종료 직전 시작된 반복은 k6의 graceful stop으로 설정 시간보다 늦게 끝날 수 있다.

## 테스트 데이터 준비

공통 [시드](../seed/README.md)는 계정·토큰과 드롭·경매 데이터를 만들며 티켓팅 회차·좌석은 포함하지 않는다. 별도의 `perf/seed/ticketing.sh seed`는 공통 시드가 준비된 빈 티켓팅 회차 환경에 1,000석 회차를 만든다. 팀원이 이미 데이터를 준비했다면 다시 시드하지 않는다. 로컬 개발용 [100석 샘플](../../local/ticketing/README.md)은 별도이며, 쓰기 실험은 실험마다 같은 초기 상태로 복원해 비교한다.

## 좌석 조회와 선점 혼합

조회 사용자와 선점 사용자를 **서로 다른 계정**으로 나눈다. `PARTICIPANTS`는 조회 사용자 수이고 `HOLD_PARTICIPANTS`는 선점 사용자 수다. CSV 앞부분의 조회 사용자 다음 행부터 선점 사용자를 사용하므로, 아래 예시는 서로 다른 계정 120개 이상이 필요하다. 각 선점 사용자는 테스트 시작 `HOLD_DELAY_SECONDS`초 후 한 번만 요청하며, 조회 사용자는 그 전후 계속 조회한다. 총 가상 사용자는 두 숫자의 합이다. `DURATION`은 선점 지연보다 충분히 길게 지정한다.

```bash
# 위의 AWS 주소·회차·CSV 환경변수를 설정한 뒤 레포 루트에서 실행
PARTICIPANTS=100 HOLD_PARTICIPANTS=20 \
HOLD_DELAY_SECONDS=3 HOT_SEATS=1 \
POLL_ENTRY=spread DURATION=30s RUN_ID=mixed100-20 \
SUMMARY_PATH="$PWD/.ai-output/perf/ticket-mixed100-20.json" \
k6 run perf/scenarios/ticketing/mixed.js
```

`ticketing/mixed.js`는 처음 조회한 `AVAILABLE` 좌석 중 `HOT_SEATS`개를 나눠 경쟁한다. 정상 선점 `201`, 경쟁상 `SEAT_UNAVAILABLE`과 `SEAT_LOCK_TIMEOUT`은 별도로 집계한다. `ticketing/mixed-distributed.js`는 선점 사용자마다 다른 좌석을 요청하며, 선점 사용자 수 이상의 가용 좌석이 필요하다. 이 경우 선점 경쟁으로 인한 409도 예상 밖 응답이다. `POLL_ENTRY=simultaneous`를 선택하면 조회 사용자도 동시에 진입한다. `VUS`는 혼합 모드의 가상 사용자 수에 영향을 주지 않는다.

예상 밖 오류율(`ticket_poll_errors`, `ticket_hold_errors`), 선점 시작·완료 수(`ticket_hold_attempts`, `ticket_hold_completed`), 조회 수(`ticket_poll_requests`), 결과별 `ticket_outcomes`와 조회·선점 지연을 각각 확인한다. 선점 시작 또는 완료 수가 설정한 사용자 수와 다르거나 조회 요청이 없으면 실패한다. `409 SEAT_LOCK_TIMEOUT`은 경쟁 결과로 분리하지만 성능 저하 가능성을 따로 조사한다. 전송 오류·401·다른 409·5xx는 실패다. k6의 예상 응답과 DB의 최종 정합성을 모두 점검해야 한다.

혼합 모드는 실제 선점 데이터를 생성한다. 테스트 회차와 계정을 팀원과 확인하고, 쓰기 실험 간 초기 상태를 복원하거나 새 회차·계정을 준비한다. 같은 `RUN_ID`와 계정의 재사용은 기존 예약 재응답으로 이어질 수 있으므로 실행마다 새 `RUN_ID`를 사용한다. 활성 예약과 좌석 소유권 등은 아래 정합성 점검으로 확인한다. 첫 회차에서 선점이 집중되며, 테스트 전체에 균일한 선점 도착률을 제공하는 시나리오는 아니다.

## 기존 선점 시나리오

쓰기 실험은 원하는 시나리오 파일이나 짧은 실행 명령을 선택한다. `PARTICIPANTS`는 서로 다른 요청 사용자 수, `VUS`는 동시 실행 수다. `MAX_DURATION` 기본값은 5분이다. 짧은 실험과 신선한 데이터에서 실행한다.

| 파일 | 동작 | 예상 결과 |
|---|---|---|
| `ticketing/distributed.js` | 사용자별 서로 다른 좌석 1개 | 전원 201. 좌석은 참여자 수 이상 필요 |
| `ticketing/hot.js` | `HOT_SEATS`개(기본 1)에 사용자 분산 | 201 또는 409 SEAT_UNAVAILABLE/SEAT_LOCK_TIMEOUT |
| `ticketing/overlap.js` | 사용자들이 [A,B] 또는 [B,C]에 경쟁 | 201 또는 위 경쟁 409. 회차 한도 2 이상 필요 |
| `ticketing/replay.js` | 각자 다른 좌석 선점 후 같은 키·본문 재전송 | 201의 reservationId가 일치 |

선점 모드는 요청 시작·완료 수가 `PARTICIPANTS`와 다르면 실패하며, replay는 재요청 완료 수도 확인한다. `MAX_DURATION`에 도달해 일부만 실행된 결과는 성능 통과가 아니다. 실험마다 다른 `RUN_ID`를 사용한다. 읽기 모드에서 `VUS=PARTICIPANTS`여야 한다. 쓰기 모드는 고정 횟수를 가능한 빠르게 실행하므로 별도로 요청 도착률을 보장하지 않는다. 좌석 선점은 만료될 수 있으니 실행 시간이 5분을 넘으면 결과를 해석하기 어렵다. `shared-iterations`의 완료 횟수가 참여자 수와 같은지도 확인한다.

## 결과와 검증

`ticket_outcomes`의 outcome 태그, `ticket_unexpected`, `ticket_poll_duration` 및 `ticket_hold_duration`의 p95/p99, `http_reqs`, `iterations`를 본다. 성공과 경쟁 실패 지연을 구분하며, 응답 코드만 409인 다른 오류는 예상 밖으로 분류한다. 임계값은 예상 밖 응답이 0인지와 선점 요청의 시작·완료 수를 확인한다. 같은 토큰을 여러 사용자에게 재사용하면 시작 전에 실패한다. 409 중 일부는 k6 기본 `http_req_failed`에 포함되지 않으므로 `ticket_unexpected`와 결과별 `ticket_outcomes`를 함께 본다. 지연 목표는 측정 전 합의한다. 실행 전후 서버 Prometheus 지표를 저장하고 CloudWatch에서 앱·DB·커넥션 풀·CPU 크레딧을 확인하며, 부하 발생기 CPU와 실제 요청률도 기록한다. 웹 대시보드는 `K6_WEB_DASHBOARD=true`와 `K6_WEB_DASHBOARD_EXPORT=<파일경로>`로 선택적으로 사용할 수 있다. 결과 JSON의 `conditions`에 인원·실험 모드·회차·시간 등 실행 조건이 저장된다. 장비·배포 버전·DB 규모·네트워크 조건과 정합성 결과는 별도로 함께 기록한다. 이 도구의 관리 포트 확인은 앱의 perf 설정을 증명하는 인증 수단이 아니므로 실행 대상과 프로필을 별도로 확인한다.

선점·혼합 실험 직후 테스트 DB 접속 환경을 설정하고 실험 회차 ID를 전달한다. DB 이름은 `perf-`로 시작해야 하며, 허용한 DB 호스트에서만 실행된다. `MYSQL`은 필요하면 [공통 시드 안내](../seed/README.md)의 Docker mysql 명령을 사용할 수 있다.

```bash
# DB_HOST, DB_NAME, DB_USERNAME, DB_PASSWORD, PERF_ALLOWED_DB_HOSTS 설정 후
perf/seed/ticketing.sh verify 123  # 123을 실제 회차 ID로 바꾼다
```

검사는 하나의 읽기 전용 일관된 스냅샷에서 14개 위반 건수를 출력한다. 모든 건수가 0일 때만 종료 코드 0이다. 없는 회차·좌석 없는 회차, DB 오류 또는 불완전한 출력도 실패다. 현재 소유 포인터와 상태, 활성 예약·선점, 구매 관리 행·선점/확정 수량, 판매 좌석의 티켓 존재를 검사한다. 모든 결제·주문과 재요청·다중 좌석 부분 성공을 증명하는 검사는 아니므로 응답과 DB를 추가 대조한다. 다른 요청 및 스케줄러가 진행 중이면 한 시점의 스냅샷만 보며, 실험 종료 후 상태가 안정된 시점에 실행한다. 성능 결과와 별도로 기존 동시성 테스트가 필요하다.

## 로컬 시나리오 검증

레포 루트에서 실행한다. Python 표준 라이브러리와 설치된 k6를 사용하며, 로컬 모의 HTTP 서버에만 요청한다. 실제 앱·AWS·DB 부하 테스트는 아니다. 결과와 임시 파일은 `.ai-output/ticketing-k6/`에 생성한다.

```bash
(
  set -e
  for test_file in perf/scenarios/tests/ticketing/verify_*.py; do
    python3 "$test_file"
  done
)
```
