# k6 시나리오

## 티켓팅

좌석 조회는 `ticketing/spread.js`(분산 진입)와 `ticketing/simultaneous.js`(동시 진입)를 사용한다. 선점과 혼합을 포함한 모든 티켓팅 시나리오는 `run-ticketing.sh`로 실행한다. `users.csv`는 공통 시드가 만든 서로 다른 사용자 토큰을 사용하며, 파일은 Git에 올리지 않는다.

```bash
export BASE_URL="http://<테스트-서버>:8080"
export PERFORMANCE_ID=<회차_ID>
export USERS_CSV="$PWD/perf/seed/out/users.csv"

perf/scenarios/run-ticketing.sh spread 100
perf/scenarios/run-ticketing.sh simultaneous 100
```

숫자는 전체 사용자 수다. 조회의 기본 실행 시간은 2분이며, `DURATION=10s perf/scenarios/run-ticketing.sh spread 5`처럼 바꿀 수 있다. CSV 대신 시드 계정으로 로그인하려면 `SETUP=self`를 지정한다. 로그인은 Refresh Token을 DB에 추가한다. HTTP 사용 시 비밀번호와 토큰은 암호화되지 않는다. 시작 전에 perf 프로필, 테스트 DB, 회차와 토큰 유효기간을 확인한다.

다른 시나리오는 `hot`, `overlap`, `distributed`, `replay`, `mixed`, `mixed-distributed`다. 선점·혼합 요청은 좌석 상태를 변경하므로 실험 간 복원과 정합성 점검이 필요하다. 실행마다 `.ai-output/perf/`에 결과가 저장된다.

[성능 실험 계획](../../docs/testing/performance.md)의 실험을 돌리는 k6 스크립트다. 입력 데이터는 [시드](../seed/README.md)가 만든다.
드롭·경매·티켓팅 시나리오를 둔다. 팬미팅은 [fanmeeting.md](fanmeeting.md)에 따로 둔다.

| 파일 | 실험 | 내용 |
|---|---|---|
| [drop-reservation-spike.js](drop-reservation-spike.js) | EXP-01 쓰기 기준선 | 판매 시작 순간 핫 드롭 재고 점유 스파이크. 대기열 없음 |
| [drop-reservation-step.js](drop-reservation-step.js) | EXP-01 용량 측정 | 도착률을 계단식으로 올려 무릎 지점과 최대 지속 처리량을 찾는다. 대기열 없음 |
| [auction-burst.js](auction-burst.js) | AU-1~4 | 두 판(기본 8,000건 → 10,000건)을 한 번에 보내는 경매 API 부하 |
| [auction-sse.mjs](auction-sse.mjs) | AU-5 | SSE 연결 계단, 유지, 가격 변경 전달 지연 |
| [auction-run.sh](auction-run.sh) | AU-1~5 | 서버 지표 수집과 시나리오 실행, 결과 파일 관리 |
| [sample-actuator.sh](sample-actuator.sh) | AU-1~5 | Actuator Prometheus 지표를 2초 간격으로 CSV 수집 |
| `fanmeeting-*` ([설명](fanmeeting.md)) | 팬미팅 S1, FM-EXP-05 | 오픈·마감 응모 몰림, 추첨, 결과 조회 폭주. `fanmeeting-s1.sh` 하나로 한 회 실행 |

두 시나리오는 [lib/drop-reservation.js](lib/drop-reservation.js)의 호스트 안전장치, 요청, 결과 분류, 요약을 같이 쓴다. 다른 것은 부하 모델뿐이다.

## drop-reservation-spike.js

`POST /api/drops/{id}/reservations`만 친다. 대기열과 결제는 재지 않는다.

- 대기열은 끈다. 입장 작업이 1초 간격으로 드롭마다 100명(`admit-per-run`)만 허가해서 대기열을 켜면 이 값이 처리량 상한이 된다. 그러면 재고 경합이 보이지 않는다. 대기열 효과는 EXP-02에서 잰다.
- 부하 모델은 `ramping-arrival-rate`다. 도착률을 `RAMP` 동안 `PEAK_RATE`까지 올리고 `HOLD` 동안 유지한 뒤 5초에 걸쳐 내린다.
- 반복마다 `users.csv`의 다른 행을 쓴다. 한 계정은 테스트에서 한 번만 요청한다. 경쟁률은 쓰는 행 수로 정한다. 참여자는 총재고 x `RATIO`다.
- SKU는 계정 순서대로 돌아가며 고른다. 핫 드롭 SKU 3개의 재고가 거의 같아서 셋이 비슷한 시점에 품절된다.
- `Idempotency-Key`는 계정마다 하나다.

### 앱과 DB 준비

실험마다 [시드 README](../seed/README.md#스냅샷과-복원) 순서를 따른다. 스냅샷을 복원하고 `seed.sh --refresh`를 실행한 뒤 앱을 띄운다.
앱은 perf 프로필에 대기열을 끄고 띄운다. perf 프로필은 가짜 PG를 쓰고 관리 포트 8081에 지표를 연다.

```bash
export SPRING_PROFILES_ACTIVE=perf RESTLESS_DROP_QUEUE_REQUIRED=false
export DB_URL=jdbc:mysql://<perf DB 호스트>:3306/perf-restless DB_USERNAME=... DB_PASSWORD=... JWT_SECRET=...   # 시드와 같은 JWT_SECRET
java -jar backend/build/libs/restless-0.0.1-SNAPSHOT.jar
```

### 실행

k6는 Docker 이미지 `grafana/k6:2.4.0`으로 돌린다. 레포 루트에서 실행한다.

```bash
docker run --rm -v "$PWD/perf:/perf:ro" -v "$PWD/.ai-output/perf:/out" \
  --add-host perf-app:10.0.1.10 \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SEED_OUT=/perf/seed/out \
  -e SUMMARY_PATH=/out/drop-spike.json \
  grafana/k6:2.4.0 run /perf/scenarios/drop-reservation-spike.js
```

앱 호스트 이름은 `perf-`로 시작해야 한다. IP는 `--add-host perf-app:<IP>`로 이름을 붙인다. 로컬에서 앱을 띄웠으면 `--add-host perf-app:host-gateway`를 쓴다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL`, `MANAGEMENT_URL` | localhost 8080, 8081 | 앱 서비스 포트와 관리 포트 |
| `PERF_ALLOWED_APP_HOSTS` | 없음 (필수) | 칠 수 있는 호스트 목록. 쉼표로 구분. 모두 `perf-`로 시작해야 한다 |
| `SEED_OUT` | `../seed/out` | `users.csv`, `manifest.json` 위치 |
| `RATIO` / `PARTICIPANTS` | manifest 경쟁률 / 총재고 x `RATIO` | 쓰는 계정 수. base에서 `RATIO=100`이면 10만 명 |
| `PEAK_RATE`, `RAMP`, `HOLD` | 1000, 5s, 20s | 최고 도착률(초당 반복), 오르는 시간, 유지 시간 |
| `PRE_VUS`, `MAX_VUS` | 500, 3000 | 미리 만들 VU, 최대 VU |
| `SUMMARY_PATH` | 없음 | JSON 요약 경로. 없으면 화면 요약만 출력 |
| `ROW_OFFSET` | 0 | `users.csv` 앞에서 건너뛸 행 수. 같은 시드로 다시 실행할 때 앞 실행이 쓴 계정을 건너뛴다 |

총 도착 수가 참여자보다 많으면 남는 반복은 요청 없이 끝나고 `skipped_no_account`로 센다. 모든 계정이 요청했는지는 결과 합계가 참여자 수와 같은지로 본다.

### 안전장치

- `BASE_URL`과 `MANAGEMENT_URL`의 호스트가 `perf-`로 시작하지 않거나, `PERF_ALLOWED_APP_HOSTS`에 정확히 없거나, 이름에 prod가 들어 있으면 시작하지 않는다. 성능 테스트 호스트만 `perf-`를 붙이고 배포 호스트에는 붙이지 않는다.
- `setup`이 관리 포트의 `/actuator/prometheus`를 확인한다. 응답이 없으면 perf 앱이 아니므로 멈춘다.
- 핫 드롭이 `ON_SALE`이 아니면 멈춘다. 이때는 `seed.sh --refresh`를 실행한다.
- `DROP_ADMISSION_REQUIRED`나 `DROP_NOT_ON_SALE`이 나오면 다음 임계값 판정 때 테스트를 중단한다. 대기열이 켜져 있거나 판매 기간이 틀렸다는 뜻이다.

### 결과 해석

응답을 아래처럼 나눠 센다. 403과 409는 `http_req_failed`에 넣지 않는다.

| 지표 | 응답 | 기대 |
|---|---|---|
| `drop_reservation_created` | 201 | 총재고 이하. 넘으면 임계값 실패 |
| `drop_sold_out` | 409 `DROP_SOLD_OUT` | 정상 품절 |
| `drop_active_purchase_exists` | 409 `DROP_ACTIVE_PURCHASE_EXISTS` | 0. 계정을 한 번만 쓰므로 나오면 조사한다 |
| `drop_reservation_replayed` | 200 같은 키 재요청 | 0. 나오면 임계값 실패 |
| `drop_admission_required`, `drop_not_on_sale` | 403, 409 | 0. 나오면 바로 멈춘다 |
| `drop_other_client_error` | 그 밖의 4xx | 0 |
| `drop_server_error`, `drop_transport_error` | 5xx, 연결 오류와 타임아웃 | 0 |

- 지연은 `drop_reservation_duration`에 있고 `outcome:created`, `outcome:sold_out`으로 나눠 볼 수 있다. 지연 목표 수치는 아직 정하지 않아서 임계값을 두지 않는다.
- `dropped_iterations`는 VU가 모자라 k6가 시작하지 못한 반복이다. 결과 합계가 참여자보다 적으면 `MAX_VUS`를 늘리거나 부하 발생기가 한계인지 본다.
- 측정이 끝나면 `seed.sh --verify`를 실행한다. 드롭 불변식, 금액, FK 고아 행 검사는 0이어야 한다. `seed baseline` 검사는 부하 뒤에 실패하는 것이 정상이다.
- 앱 지표는 관리 포트의 `/actuator/prometheus`에서 본다. `drop.reservation.duration`, HikariCP, 톰캣 스레드 지표가 있다.

- 실행 화면은 k6 웹 대시보드(`K6_WEB_DASHBOARD=true`, `-p 5665:5665`)로 보고, `K6_WEB_DASHBOARD_EXPORT`로 HTML 리포트를 `.ai-output/perf/`에 남긴다.
- 서버 지표는 실행 직전과 직후에 `curl -s http://perf-app:8081/actuator/prometheus`를 파일로 저장해 차이를 본다. 실행 전체의 서버 평균 지연과 Hikari, 톰캣 상태를 얻는다.
- EC2에서 준비하는 순서는 [ec2](../ec2/README.md)에 있다.

### 로컬 확인 (2026-11-16, 측정 결과 아님)

시나리오가 도는지만 확인했다. 앱, MySQL, k6를 맥북 한 대에서 같이 돌렸으므로 지연은 기준선이 아니다.
small 시드는 재고 100개, 참여자 1,000명이다. 첫 실행은 시드 직후, 두 번째는 덤프 복원과 `--refresh` 뒤에 돌렸다.

| 도착률 | 점유 성공 | 품절 | 서버 오류 | 그 밖 | `--verify` |
|---|---|---|---|---|---|
| 200/s | 100 | 900 | 0 | 0 | 시드 기준값 외 0 |
| 1000/s | 100 | 900 | 0 | 0 | 시드 기준값 외 0 |
| 200/s, DB `perf-restless`, 호스트 `perf-app` | 100 | 900 | 0 | 0 | 시드 기준값 외 0 |

## drop-reservation-step.js

스파이크와 같은 요청을 계단식 도착률로 보낸다. 계단마다 `STEP_RAMP` 동안 올리고 `STEP_HOLD` 동안 유지한다.
p95가 꺾이거나 5xx, 타임아웃이 생기는 무릎 지점을 찾는다. 그 직전 계단이 최대 지속 처리량이다.

### 재고를 크게 두는 이유

base 재고 1,000은 200/s에서 5초 만에 품절된다. 그 뒤로는 품절 응답만 재게 된다. 품절 경로는 조건부 UPDATE가 0행으로 끝나 주문과 점유를 만들지 않으므로, 성공 점유 경로보다 훨씬 가볍다.
그래서 용량 측정은 시드 `--scale capacity`를 쓴다. 재고와 계정이 21만이라 기본 계단의 계획 도착 수 약 19만 9천을 넘는다.
핫 SKU 3개, 배경 데이터 크기는 base와 같다. 행 경합과 테이블 크기 조건은 그대로고 재고 수량만 다르다.
계획 도착 수가 재고보다 크면 시작하지 않는다. 품절까지 포함해 재려면 `ALLOW_SOLD_OUT=1`을 준다.
실제 드롭 오픈처럼 품절까지 가는 경합은 base 시드와 스파이크 시나리오로 잰다.

### 실행

```bash
docker run --rm -v "$PWD/perf:/perf:ro" -v "$PWD/.ai-output/perf:/out" -p 5665:5665 \
  --add-host perf-app:3.35.88.242 \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SEED_OUT=/perf/seed/out \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_EXPORT=/out/step.html \
  -e STEPS=50,100,200,400,800 -e SUMMARY_PATH=/out/step.json \
  grafana/k6:2.4.0 run /perf/scenarios/drop-reservation-step.js
```

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `STEPS` | 50,100,200,400,800 | 계단별 도착률(초당 반복) |
| `STEP_RAMP`, `STEP_HOLD` | 10s, 2m | 계단 사이 오르는 시간, 계단 유지 시간. `30s`, `2m` 형식 |
| `ABORT_ERROR_RATE` | 0.05 | 5xx, 연결 오류, 타임아웃의 누적 비율이 이 값을 넘으면 멈춘다. 30초 뒤부터 판정한다. 1이면 멈추지 않는다 |
| `ALLOW_SOLD_OUT` | 없음 | 1이면 재고가 계획 도착 수보다 적어도 실행한다 |
| `PRE_VUS`, `MAX_VUS` | 200, 3000 | 미리 만들 VU, 최대 VU |
| `ROW_OFFSET`, `SEED_OUT`, `SUMMARY_PATH` | | 스파이크와 같다 |

스모크와 계단식을 한 시드로 돌릴 때는 스모크가 쓴 계정 수만큼 `ROW_OFFSET`을 준다. 예: `STEPS=10,20 STEP_HOLD=30s`는 약 1,000행을 쓴다.

### 결과 해석

- 요청은 `stage` 태그를 단다. 유지 구간은 도착률 값이고, 오르는 구간은 `ramp`다. 요약은 유지 구간만 계단별로 보여 준다.
- 계단별 `achieved`는 유지 구간 요청 수를 유지 시간으로 나눈 값이다. 목표보다 낮으면 `dropped_iterations`와 노트북 CPU를 먼저 본다.
- 결과 분류와 정합성 판정은 스파이크와 같다. 측정 뒤 `seed.sh --verify`를 실행한다.
- 무릎 지점은 계단별 k6 지연과 오류율로 판단한다. k6 지연에는 집 네트워크 왕복이 섞이므로 테스트 전 왕복 지연을 같이 기록한다.
- 계단별 서버 지연이 필요하면 계단을 따로 실행하고 실행마다 `/actuator/prometheus` 전후 값을 저장한다.

## Auction burst testing: AU-1 to AU-5

Run against a perf-profile app with the base seed loaded once. The runner logs in as the organizer and creates fresh auctions as needed. Created auctions remain in the database, so run each k6 scenario against a restored perf database.

Use k6 2.4.0 installed on macOS (brew install k6) and Node 24. Set PERF_APP_IP in the shell; the runner maps the address to perf-local and does not write the IP into the repository or result files.

    export PERF_APP_IP=192.0.2.10
    PHASES=200,300 perf/scenarios/auction-run.sh detail local-detail
    PHASES=200,300 perf/scenarios/auction-run.sh list local-list
    PHASES=200,300 LOGIN_RATE=50 perf/scenarios/auction-run.sh bid local-bid
    PHASES=200,300 LOGIN_RATE=50 PHASE_GAP_SECONDS=240 perf/scenarios/auction-run.sh ending local-ending
    STEPS=50,100 ACCOUNTS=20 HOLD_SECONDS=20 perf/scenarios/auction-run.sh sse local-sse

The default k6 run sends 8,000 requests followed by 10,000 in one run. PHASES can reduce counts for local checks. auction-run.sh starts the Actuator sampler and stops it after the scenario.

| ID | Runner name | API / authentication | Purpose |
|---|---|---|---|
| AU-1 | ending | Bid POST, login | Final-second bids, rejections before the deadline, extension and winner correctness |
| AU-2 | detail | Public detail GET | Burst reads of one auction |
| AU-3 | list | Public auction-list GET | Burst reads of OPEN auctions |
| AU-4 | bid | Bid POST, login | Ordinary bid burst |
| AU-5 | sse | Authenticated SSE GET | Connection lifetime and update delivery delay |

AU-2 and AU-3 currently use public GET endpoints and send no token. A 401 is reported as the public GET path being blocked.

### k6 options

| Variable | Default | Meaning |
|---|---|---|
| TARGET | set by runner | ending, detail, list, or bid |
| PHASES | 8000,10000 | Two ascending request counts |
| BIDDER_FIRST_ID, BIDDER_LAST_ALLOWED_ID | 1, 96900 | Consecutive bidder IDs; exceeding the limit stops the run |
| LOGIN_RATE, LOGIN_MARGIN_SECONDS | 15, 60 | Login rate per second and margin after estimated login completion |
| PHASE_GAP_SECONDS | 60 ordinary, 240 ending | Delay between phases |
| AMOUNT_STEPS | 100 | Random minimum-increment multiples above the starting price |
| ENDING_LEAD_MS | 1000 | Original ending time after the phase start |
| START_DELAY_SECONDS, AUCTION_DURATION_SECONDS | 20, 7200 | Auction start delay and ordinary auction duration |
| REQUEST_TIMEOUT_SECONDS, RESULT_WAIT_SECONDS | 60, 300 | Request timeout and result polling limit |
| ORGANIZER_EMAIL, SEED_PASSWORD | organizer1@fanq.com, 0000 | Seed organizer and password |

ending, bid, and detail create one auction per phase. list creates none. The auction opens at server time plus START_DELAY_SECONDS. ending sets the original end to phase T plus ENDING_LEAD_MS. Each bidder logs in once and sends one request in each phase they join. At 15 logins per second, 10,000 logins take about 11 minutes, plus the configured margin.

### SSE options

auction-sse.mjs uses Node 24's built-in fetch. It creates a long-running auction, logs in observer accounts, and reuses their tokens across connections. A separate account places one bid at each connection step. Default cumulative steps are 2,000, 4,000, 6,000, 8,000, and 10,000.

| Variable | Default | Meaning |
|---|---|---|
| STEPS | 2000,4000,6000,8000,10000 | Cumulative open connection targets |
| ACCOUNTS, BIDDER_FIRST_ID, BIDDER_LAST_ALLOWED_ID | 1000, 1, 96900 | Observer account count and range; the next two IDs are alternating measurement bidders |
| LOGIN_RATE | 15 | Logins per second; 1,000 observers take about 67 seconds |
| CONNECT_RATE | 200 | New SSE connections per second |
| HOLD_SECONDS | 60 | Hold time at each connection step |
| CONNECT_WAIT_SECONDS, PROPAGATION_WAIT_SECONDS | 30, 15 | Wait limits for initial and changed events |
| POST_RUN_CLOSE_WAIT_SECONDS | 90 | Wait for the scenario auction's final CLOSED event after the last step (includes the 60-second bid extension) |
| PERF_APP_IP | required | Target IP, supplied only through the environment |
| PERF_APP_HOST, PERF_ALLOWED_APP_HOSTS | perf-local | Logical Host name and allow-list |

Each step reports attempted, successful (HTTP 200 and first event), failed by status, disconnected, active, updated connections, and event-delivery p50/p95/max. Two measurement accounts, separate from observer IDs, alternate bids because the auction rejects consecutive bids from the current highest bidder. The script sizes the auction lifetime to the login and connection plan, then waits for the final CLOSED event. If connections remain, it closes the clients and sends one separately reported cleanup bid so the server attempts a final event and can remove disconnected emitters. The bid response has no revision field, so the expected revision is the OPEN revision plus the number of successful measurement bids.

### Results and interpretation

auction-run.sh ending|detail|list|bid|sse [name] writes results under .ai-output/perf/auction/. JSON contains conditions, per-phase sends/responses/connection failures, status and error counts, latency, bid reconciliation, and ending or SSE results. k6 runs also produce an HTML dashboard. All runs save a log and a two-second Actuator CSV.

Each phase records sent and received requests, connection failures, 5xx, response codes, latency p50/p95/p99/max, responses within one second of T, last response time from T, and send-time spread. Bid runs compare 201 responses with server-accepted bids. Non-zero 5xx, connection failures, bid-count differences, winner mismatch, or AUCTION_CLOSED before the original deadline are prominently marked. There is no pass threshold.

The CSV samples Hikari active, pending and timeout, Tomcat busy threads and current connections, process and system CPU, auction_stream_connections, and the sum of http_server_requests_active_seconds_gcount. Missing metrics or an unavailable response are blank.

### Safety and correctness

The application host must start with perf-, appear in PERF_ALLOWED_APP_HOSTS, and contain no prod text. Before the run, the management endpoint must return 200 for /actuator/prometheus. Passwords and tokens are never written to logs or summaries.

After the day's experiments, run perf/ec2/perf-ec2.sh verify base once to inspect invariants. Seed-baseline checks can fail after load has changed data. For an ending auction, server accepted bids equal revision minus 2 (OPEN +1, each bid +1, close +1); for a still-open bid auction, use revision minus 1.
