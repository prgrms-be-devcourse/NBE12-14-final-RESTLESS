# k6 시나리오

## 티켓팅

좌석 조회는 `ticketing/spread.js`(분산 진입)와 `ticketing/simultaneous.js`(동시 진입)를 사용한다. 선점과 혼합을 포함한 모든 티켓팅 시나리오는 `run-ticketing.sh`로 실행한다. `users.csv`는 공통 시드가 만든 서로 다른 사용자 토큰을 사용하며, 파일은 Git에 올리지 않는다.

조회·페이지·혼합 시나리오는 사용자당 최초 `/seats` 요청을 한 번 수행한다. 자동 폴링은 없다. `REFRESH_COUNT`를 지정하면 각 사용자가 수동 새로고침 버튼을 누르는 상황을 가정해 `/seats/status`를 지정 횟수만큼 조회한다. 목록이 일치하지 않으면 전체 좌석을 다시 조회한다. `burst`는 최초 전체 목록 조회만 한 번 수행한다.

```bash
export BASE_URL="http://<테스트-서버>:8080"
export MANAGEMENT_URL="http://<테스트-서버>:8081"
export PERF_ALLOWED_APP_HOSTS="<테스트-서버>"
export PERFORMANCE_ID=<회차_ID>
export USERS_CSV="$PWD/perf/seed/out/users.csv"

perf/scenarios/run-ticketing.sh spread 100
perf/scenarios/run-ticketing.sh simultaneous 100
perf/scenarios/run-ticketing.sh page 100
```

숫자는 전체 사용자 수다. 각 사용자는 한 번 진입하며 `DURATION`은 적용되지 않는다. `MAX_DURATION`(기본 5분)은 완료 제한 시간이다. 예: `REFRESH_COUNT=2 REFRESH_INTERVAL_SECONDS=30 perf/scenarios/run-ticketing.sh page 100`은 사용자당 30초 간격으로 두 번의 수동 새로고침을 가정한다. 실제 사용자의 새로고침 빈도를 뜻하지 않는다. 기존 3초 폴링 결과와 요청 수 및 응답시간을 직접 비교하지 않는다. 실행기는 CSV 계정을 사용하며, 같은 서버 주소·회차에서 이미 배정한 사용자 ID를 `.ai-output/perf/ticketing-account-ledger.sqlite3`에 기록하고 건너뛴다. 과거 요약에 계정 정보가 있으면 해당 기록도 반영한다. 실패·중단된 실행의 배정도 유지한다. 직접 JS를 실행하거나 다른 컴퓨터에서 실행한 결과, 다른 주소로 접근한 같은 서버의 기록은 공유되지 않으므로 별도 조율이 필요하다. HTTP 사용 시 토큰은 암호화되지 않는다. 시작 전에 perf 프로필, 테스트 DB, 회차와 토큰 유효기간을 확인한다.

`page`는 로그인 완료 사용자의 첫 진입에서 좌석과 구매 가능 정보를 병렬 조회하고 기본적으로 추가 조회 없이 종료한다. 기본 진입은 3초 분산이며 `POLL_ENTRY=simultaneous`로 동시 진입을 지정한다. 포커스 복귀·선점 이후의 갱신은 포함하지 않는다. 구매 가능 정보 API가 배포된 이미지가 필요하다. `ticket_availability_requests`, `ticket_availability_errors`, `ticket_availability_duration`으로 해당 API를 따로 확인한다.

다른 시나리오는 `hot`, `overlap`, `distributed`, `replay`, `mixed`, `mixed-distributed`다. 선점·혼합 요청은 좌석 상태를 변경하므로 실험 간 복원과 정합성 점검이 필요하다. 요약 결과는 `.ai-output/perf/ticketing-<시나리오>/summary-<UTC 날짜시간>.json`, HTML 보고서는 같은 폴더의 `report-<UTC 날짜시간>.html`에 자동 저장된다. 예: `.ai-output/perf/ticketing-spread/summary-20261118T010203Z.json`. k6 표준 출력과 오류는 같은 폴더의 `k6-<UTC 날짜시간>.log`에 저장되며 터미널에도 표시된다. 실행 중에는 `http://localhost:5665`에서 대시보드를 볼 수 있다. 실행기가 기본으로 대시보드와 HTML 내보내기를 켜므로 별도 k6 환경변수를 지정하지 않아도 된다.

`burst`는 준비된 각 사용자가 정해진 시각에 좌석 조회를 한 번씩 보낸다. 예를 들어 `perf/scenarios/run-ticketing.sh burst 5000`은 서로 다른 미사용 토큰 5,000개가 필요하다. 실행 전 좌석 및 관리 지표 확인 요청이 각각 한 번 추가된다. 기본적으로 준비 완료 20초 후 시작하며 `BURST_DELAY_SECONDS=30`으로 조절한다. `DURATION`은 적용되지 않고 `MAX_DURATION`(기본 5분)에 준비 대기와 응답 대기를 포함한다. `ticket_poll_requests`와 오류 임계값으로 완료 여부를 확인하고, `ticket_burst_start_delay`의 p95·최댓값으로 실제 요청 시작이 얼마나 퍼졌는지 살핀다. 이 값은 요청 전 로컬 시각 기준이며 서버 도착 시각은 아니다. 부하 생성기 성능이나 VU 초기화 때문에 설정 인원이 정확히 동시에 도착한다고 보장하지 않는다. 각 테스트 환경에 필요한 수만큼의 유효하고 미사용인 계정을 준비한다.

좌석 응답 압축 비교는 같은 서버와 데이터 조건에서 `SEAT_ENCODING=identity perf/scenarios/run-ticketing.sh burst 500`과 `SEAT_ENCODING=gzip perf/scenarios/run-ticketing.sh burst 500`을 각각 실행한다. 기본값은 identity다. gzip을 요청했는데 준비 요청에서 압축되지 않으면 테스트를 중단하며, 본 요청의 `ticket_seat_encoding_errors`와 `ticket_seat_gzip_responses`로 적용 여부를 확인한다. 2KB 미만 응답은 서버가 압축하지 않으므로 gzip 비교에 적합하지 않다. 요약의 `conditions.seatEncoding`, `data_received`, 응답 시간과 서버 CPU를 함께 비교한다. 서버는 회차별 좌석 조회 JSON에만 압축을 적용한다. `HTTP_COMPRESSION_ENABLED=false`로 압축을 끌 수 있고 변경 후 앱을 재시작해야 한다.

### 테스트 중 서버 지표를 CSV로 수집

`run-ticketing.sh`가 테스트 시작 전 수집기를 자동으로 시작하고, 테스트 종료 후 마지막 지표를 저장한다. 관리 포트에 연결되지 않으면 테스트를 시작하지 않는다. 기본 간격은 2초이며 `INTERVAL=5`로 바꿀 수 있다. 별도 수집이 필요할 때는 아래 명령을 실행하고 `Ctrl-C`로 종료한다.

```bash
MANAGEMENT_URL="http://3.35.88.242:8081" \
PERF_ALLOWED_APP_HOSTS="3.35.88.242" \
python3 perf/scenarios/ticketing/collect-prometheus-csv.py simultaneous
```

CSV는 `.ai-output/perf/ticketing-simultaneous/prometheus-<UTC 날짜시간>.csv`에 저장된다. 각 행은 수집 시각, Prometheus 지표명, 라벨, 값이다. 수집기 시작 시 관리 포트 접근과 Prometheus 형식을 확인한 뒤에만 기록을 시작한다. 이 파일에는 관리 포트가 실제로 노출하는 지표만 들어간다. Tomcat·Hikari·JVM 값은 확인할 수 있지만 EC2 호스트 메모리나 별도 RDS 지표는 앱의 Prometheus에 노출되지 않으면 포함되지 않는다. 해당 지표는 CloudWatch 또는 DB 모니터링에서 별도로 수집해야 한다.

macOS에서 실행하면 같은 폴더에 `mac-<UTC 날짜시간>.csv`도 자동 저장한다. 기본 5초마다 Mac 전체 CPU 사용률, k6 프로세스 CPU 사용률(한 코어 기준, 100% 초과 가능), 서버로 향하는 네트워크 인터페이스의 전체 수신·송신 바이트와 초당 속도를 기록한다. 네트워크 값은 Mac의 해당 인터페이스 전체 트래픽이며 k6만의 사용량은 아니다. 첫 속도는 기준값이 없어 비어 있다. `MAC_METRICS_INTERVAL`(2초 이상)과 `MAC_NET_INTERFACE`로 간격과 인터페이스를 지정할 수 있다. 수집 실패는 CSV의 `errors` 열과 터미널에 표시된다. 실시간 대시보드 연결 때문에 k6 종료가 늦으면 수집도 계속되므로 분석 시 실제 부하 시간으로 구간을 제한한다.

[성능 실험 계획](../../docs/testing/performance.md)의 실험을 돌리는 k6 스크립트다. 입력 데이터는 [시드](../seed/README.md)가 만든다.
드롭·경매·티켓팅 시나리오를 둔다. 팬미팅은 [fanmeeting.md](fanmeeting.md)에 따로 둔다.

| 파일 | 실험 | 내용 |
|---|---|---|
| [drop-reservation-spike.js](drop-reservation-spike.js) | EXP-01 쓰기 기준선 | 판매 시작 순간 핫 드롭 재고 점유 스파이크. 대기열 없음 |
| [drop-reservation-step.js](drop-reservation-step.js) | EXP-01 용량 측정 | 도착률을 계단식으로 올려 포화 시작점과 최대 지속 처리량을 찾는다. 대기열 없음 |
| [drop-sale.js](drop-sale.js) | 드롭 대표 판매 측정, EXP-02 | 한정 재고 판매. 대기열 off/on을 같은 도착 시간표로 비교하고 결제까지 잇는다 |
| [drop-reservation-capacity.js](drop-reservation-capacity.js) | 드롭 용량 측정, EXP-01, 04 | 성공 점유 경로의 연속 증가 탐색과 고정 도착률 유지 |
| [drop-queue-poll.js](drop-queue-poll.js) | 드롭 대기열 폴링 측정 | 대기자 N명을 고정하고 상태 조회 비용을 잰다 |
| [prom-collect.sh](prom-collect.sh) | 드롭 성능 측정 전부 | 관리 포트의 Prometheus 텍스트를 5초 간격으로 파일에 남긴다 |
| [auction-burst.js](auction-burst.js) | AU-1~4 | 두 판(기본 8,000건 → 10,000건)을 한 번에 보내는 경매 API 부하 |
| [auction-sse.mjs](auction-sse.mjs) | AU-5 | SSE 연결 계단, 유지, 가격 변경 전달 지연 |
| [auction-run.sh](auction-run.sh) | AU-1~5 | 서버 지표 수집과 시나리오 실행, 결과 파일 관리 |
| [sample-actuator.sh](sample-actuator.sh) | AU-1~8 | Actuator Prometheus 지표를 CSV로 수집 (기본 2초, `SAMPLE_INTERVAL_SECONDS`로 변경). 서버 안 1초 수집은 `perf-ec2.sh sample`이 이 스크립트를 쓴다 |
| `fanmeeting-*` ([설명](fanmeeting.md)) | 팬미팅 S1, FM-EXP-05 | 오픈·마감 응모 몰림, 추첨, 결과 조회 폭주. `fanmeeting-s1.sh` 하나로 한 회 실행 |

드롭 시나리오는 [lib/drop-reservation.js](lib/drop-reservation.js)의 호스트 안전장치, 요청, 결과 분류, 집계, 요약을 같이 쓴다. 대기열과 결제 요청은 [lib/drop-flow.js](lib/drop-flow.js)에 있다.
드롭 측정 시나리오는 [드롭 성능 개선 계획](../../docs/testing/drop-performance-plan.md)의 측정에 쓴다. 아래 [드롭 성능 측정 시나리오](#드롭-성능-측정-시나리오)에 사용법이 있다.

## drop-reservation-spike.js

`POST /api/drops/{id}/reservations`만 친다. 대기열과 결제는 재지 않는다.

- 대기열은 끈다. 입장 작업이 1초 간격으로 SKU마다 100명(`admit-per-run`)만 허가해서 대기열을 켜면 이 값이 처리량 상한이 된다. 그러면 재고 경합이 보이지 않는다. 대기열 효과는 EXP-02에서 잰다.
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

k6 2.4.0을 노트북에 설치해서 돌린다(`brew install k6`). 레포 루트에서 실행한다.
맥의 Docker는 가상 머신의 메모리와 CPU 안에서 VU를 만든다. 2026-11-18 드롭 판매 1,000명/초 실행에서 VU가 3,748개에서 멈춰 도착 1만 명을 채우지 못했다.
맥의 열린 파일 수 기본 제한으로는 VU 수천 개의 연결을 열지 못하므로 같은 셸에서 `ulimit -n`을 먼저 올린다.

```bash
ulimit -n 65536
BASE_URL=http://perf-app:8080 MANAGEMENT_URL=http://perf-app:8081 \
PERF_ALLOWED_APP_HOSTS=perf-app SEED_OUT="$PWD/perf/seed/out" \
SUMMARY_PATH=.ai-output/perf/drop-spike.json \
k6 run perf/scenarios/drop-reservation-spike.js
```

앱 호스트 이름은 `perf-`로 시작해야 한다. `/etc/hosts`에 `<IP> perf-app`을 둔다. 로컬에서 앱을 띄웠으면 `127.0.0.1 perf-app`이다.

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
| `drop_server_error`, `drop_transport_error`, `drop_timeout` | 5xx, 연결 오류, 제한 시간(기본 5초) 도달 | 0 |

- 지연은 `drop_reservation_duration`에 있고 `outcome:created`, `outcome:sold_out`으로 나눠 볼 수 있다. 응답을 받은 요청만 들어간다. 타임아웃을 제한 시간 값으로 넣은 평가 지연은 `drop_eval_latency{api:reserve}`다.
- `dropped_iterations`는 VU가 모자라 k6가 시작하지 못한 반복이다. 결과 합계가 참여자보다 적으면 `MAX_VUS`를 늘리거나 부하 발생기가 한계인지 본다.
- 측정이 끝나면 `seed.sh --verify`를 실행한다. 드롭 불변식, 금액, FK 고아 행 검사는 0이어야 한다. `seed baseline` 검사는 부하 뒤에 실패하는 것이 정상이다.
- 앱 지표는 관리 포트의 `/actuator/prometheus`에서 본다. `drop.reservation.duration`, HikariCP, 톰캣 스레드 지표가 있다.

- 실행 화면은 k6 웹 대시보드(`K6_WEB_DASHBOARD=true`, `localhost:5665`)로 보고, `K6_WEB_DASHBOARD_EXPORT`로 HTML 리포트를 `.ai-output/perf/`에 남긴다.
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
p95가 꺾이거나 5xx, 타임아웃이 생기는 포화 시작점을 찾는다. 그 직전 계단이 최대 지속 처리량이다.

### 재고를 크게 두는 이유

base 재고 1,000은 200/s에서 5초 만에 품절된다. 그 뒤로는 품절 응답만 재게 된다. 품절 경로는 조건부 UPDATE가 0행으로 끝나 주문과 점유를 만들지 않으므로, 성공 점유 경로보다 훨씬 가볍다.
그래서 용량 측정은 시드 `--scale capacity`를 쓴다. 재고와 계정이 21만이라 기본 계단의 계획 도착 수 약 19만 9천을 넘는다.
핫 SKU 3개, 배경 데이터 크기는 base와 같다. 행 경합과 테이블 크기 조건은 그대로고 재고 수량만 다르다.
계획 도착 수가 재고보다 크면 시작하지 않는다. 품절까지 포함해 재려면 `ALLOW_SOLD_OUT=1`을 준다.
실제 드롭 오픈처럼 품절까지 가는 경합은 base 시드와 스파이크 시나리오로 잰다.

### 실행

```bash
ulimit -n 65536
BASE_URL=http://perf-app:8080 MANAGEMENT_URL=http://perf-app:8081 \
PERF_ALLOWED_APP_HOSTS=perf-app SEED_OUT="$PWD/perf/seed/out" \
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=.ai-output/perf/step.html \
STEPS=50,100,200,400,800 SUMMARY_PATH=.ai-output/perf/step.json \
k6 run perf/scenarios/drop-reservation-step.js
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
- 포화 시작점은 계단별 k6 지연과 오류율로 판단한다. k6 지연에는 집 네트워크 왕복이 섞이므로 테스트 전 왕복 지연을 같이 기록한다.
- 계단별 서버 지연이 필요하면 계단을 따로 실행하고 실행마다 `/actuator/prometheus` 전후 값을 저장한다.

## 드롭 성능 측정 시나리오

[목표](../../docs/testing/drop-performance-plan.md), [실행 절차](../../docs/testing/drop-performance-procedure.md), [측정 규약](../../docs/testing/drop-performance-measurement.md)의 측정을 돌린다. 대표 판매 측정은 `drop-sale.js`, 용량 측정은 `drop-reservation-capacity.js`, 대기열 폴링 측정은 `drop-queue-poll.js`다.
실행 명령은 위 스파이크와 같고 스크립트 이름과 환경 변수만 다르다. 원자료를 남기려면 `k6 run --out json=<결과 폴더>/<이름>.raw.json`을 붙인다.
드롭 성능을 측정할 때는 macOS 사전 검사를 통과한 뒤 k6를 실행하고, 종료 뒤 `k6.log`의 부하 발생기 판정을 보고서에 넣는다. [부하 발생기 검사와 실행 명령](../../docs/testing/drop-performance-procedure.md#7-2-서버별-지표-수집과-k6)

### 공통 집계

| 지표 | 뜻 |
|---|---|
| `iteration_started`, `iteration_finished` | 시작한 작업 S와 끝난 작업 F. 미완료 I는 S - F다. 요약의 `unfinished` 줄을 종료 로그의 interrupted와 대조한다 |
| `drop_eval_latency{api:...}` | API별 평가 지연. 응답을 받은 요청의 지연과, 제한 시간에 걸린 요청의 제한 시간 값이 들어간다 |
| `drop_unexpected_error{api:...}` | API별 예상 밖 오류율. 5xx, 연결 실패, 타임아웃, 예상하지 않은 4xx다. 분모는 그 API의 시작한 요청이다 |
| `drop_timeout`, `drop_transport_error` | 제한 시간 도달과 그 밖의 전송 실패. 전송 실패는 평가 지연에 넣지 않고 `drop_transport_failure_time`에 남긴다 |
| 태그 `win` | 요청이 시작한 창 번호. 요약에 창별 지연이 나온다 |

- 제한 시간은 `HTTP_TIMEOUT`(기본 5s)이고 결제는 `PAY_TIMEOUT`(기본 10s)이다.
- k6는 예정 도착 시각을 주지 않는다. 원자료에는 실제 시작 시각만 있다. 계획 대비 빠진 수는 `dropped_iterations`와 시작 수로 본다.
- arrival-rate는 첫 작업을 1/도착률 초 뒤에 시작한다. 계획보다 한 건 적게 시작하는 것은 정상이다.
- 창 기준 중단은 k6가 실행 중에 판정하지 못한다. 실행 중에는 사람이 보고, 판정은 끝난 뒤 요약과 원자료로 한다.

### drop-sale.js (대표 판매 측정)

회원 한 명이 iteration 하나다. off는 도착 즉시 재고를 점유한다. on은 대기 진입 뒤 `POLL_SECONDS`마다 상태를 조회하고 입장하면 점유한다. 점유에 성공하면 결제 준비와 승인까지 보낸다.
앱의 `RESTLESS_DROP_QUEUE_REQUIRED`가 `QUEUE`와 같아야 한다. 시드는 sale이다. base로도 돌지만 워밍업이 없다.
on은 회원마다 SKU 하나를 계정 순서대로 돌아가며 정하고 진입, 조회, 점유에 같은 SKU를 쓴다. off의 점유와 같은 배정이다. 입장 허가는 진입한 SKU의 점유에만 통한다.
시드에 워밍업 드롭이 있으면 판매 전에 그 드롭으로 같은 흐름을 먼저 보낸다. 워밍업은 핫 드롭 재고와 참여 계정을 쓰지 않고 요약의 판매 결과에서 빠진다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `QUEUE` | off | off 또는 on |
| `PARTICIPANTS` / `RATIO` | 총재고 x manifest 경쟁률 | 참여자 수. base는 1,000 x 10 |
| `PEAK_RATE`, `RAMP` | 1000, 5s | 최고 도착률과 0에서 오르는 시간. 유지 시간은 참여자 수에 맞춰 계산한다. 기본값이면 7.5초 |
| `MARGIN` | 200ms | 마지막 몇 명이 빠지지 않게 유지 구간에 더하는 시간. 넘친 작업은 `skipped_no_account`로 끝난다 |
| `OBSERVE` | 300s | 신규 유입이 끝난 뒤 관측 시간. 이 시각까지 입장하지 못한 회원은 `unfinished`다 |
| `POLL_SECONDS` | 2 | 서버 응답에 `pollAfterMs`가 없을 때 쓰는 조회 간격. 있으면 그 값을 따른다 |
| `PAY` | 1 | 0이면 점유까지만 한다 |
| `RETRY`, `RETRY_MAX` | 켬, 60s | 0이면 재시도를 끈다. `RETRY_MAX`는 회원별 재시도 상한 시간이고 입장 허가 유효시간(기본 5분)보다 짧게 둔다. 관측 종료 시각도 넘지 않는다 |
| `WARMUP` | 시드에 워밍업 드롭이 있으면 켬 | 0이면 워밍업을 끈다 |
| `WARM_RATE`, `WARM`, `WARM_GAP` | 30, 60s, 10s | 워밍업 도착률과 시간, 워밍업이 끝난 뒤 판매 시작까지 기다리는 시간. 계정은 참여자 뒤 행을 쓴다 |
| `WINDOW_SECONDS` | 10 | 창 길이 |
| `MAX_VUS`, `PRE_VUS` | on은 참여자 수, off는 3000 | on은 대기 중인 회원마다 VU 하나를 쓴다 |

- 축소 조건은 시드 스케일과 `PEAK_RATE`를 함께 바꾼다. 500명 축소는 `--scale sale_small`에 `PEAK_RATE=500`, 200명 축소는 `--scale sale_mini`에 `PEAK_RATE=200`이다.
- 요약의 `member results`가 회원별 최종 결과다. `paid`, `sold_out`, `unavailable`, `unfinished`, `queue_closed`, `admission_expired`, `payment_pending`, `payment_failed`, `error`로 나뉜다.
- on의 `sold_out`에는 점유 품절과 진입 거절(그 SKU의 `available = 0`, 409 `DROP_SOLD_OUT`)이 함께 들어간다. `unavailable`은 대기 중 재고가 0이 되어 조회가 `UNAVAILABLE`을 받은 회원이다. 최종 매진은 아니다.
- `first response`는 도착 뒤 첫 API의 평가 지연이다. `queue wait`는 진입 응답부터 입장을 확인한 시각까지다. `arrival to paid`는 도착부터 결제 승인까지다.
- 대기 중 회원은 조회 응답의 `pollAfterMs`만큼 쉬고 다시 조회한다. 값이 없으면 `POLL_SECONDS`를 쓴다 (2026-11-24 서버 제안 간격, #390).
- 503 `DROP_QUEUE_BUSY`는 서버가 동시 처리 상한을 넘겨 일찍 거절한 응답이다. 오류로 세지 않고 `Retry-After`에 최대 50% 지터를 더해 쉰 뒤 다시 보낸다. 요약의 `queue busy` 줄과 `drop_queue_busy` 카운터(태그 `api`는 enter, poll)에서 건수를, `drop_queue_busy_latency`에서 503 응답의 지연을 본다. 빠른 거절이 enter와 poll의 평가 지연이나 `first response`에 섞이지 않고 `unexpected` 비율에도 들어가지 않는다. 포화 구간의 p95는 busy 건수와 함께 읽는다.
- 재시도: 실제 앱처럼 점유, 결제 준비, 결제 승인이 시간 초과, 연결 오류, 5xx(503 `DROP_QUEUE_BUSY` 포함)를 받으면 같은 `Idempotency-Key`와 같은 본문으로 다시 보낸다. 점유 키는 회원과 드롭마다 하나로 고정이고 서버는 같은 `reservationId`와 `orderId`를 200으로 돌려준다 (INV-07). 4xx 업무 오류(품절, 입장 허가 없음 등)는 다시 보내지 않는다. 간격은 `Retry-After`가 있으면 그 값, 없으면 200ms부터 2배씩 늘려 2s까지이며 둘 다 최대 50% 지터를 더한다.
- 결제 승인은 서버가 READY가 아닌 시도에는 PG를 다시 부르지 않으므로 같은 키로 다시 보내도 안전하다. 승인 202(처리 중, 불명확)는 다시 승인하지 않고 `payment_pending`으로 끝낸다. 승인 요청이 응답 없이 끝나고 재시도로도 확인하지 못한 경우도 결과 불명이라 실패가 아니라 `payment_pending`으로 센다.
- 재시도 지표: 요약의 `retry` 줄에 api별(reserve, prepare, confirm) 재시도 요청 수, 재시도로 회복된 회원 수, 재시도 뒤에도 실패한 회원 수, 재시도 끝에 4xx 업무 응답(품절 등)을 받은 회원 수가 나온다 (`drop_retry_attempts`, `drop_retry_recovered`, `drop_retry_exhausted`, `drop_retry_answered`). 재시도가 받은 200은 `drop_reservation_replayed`가 아니라 `drop_reservation_created`로 센다. 첫 시도의 지연만 `eval` 줄과 `first response`에 들어간다. 재시도 응답은 `drop_retry_eval_latency`, 첫 시도 시작부터 최종 성공까지는 `drop_time_to_final`(태그 `retried`가 true, false)로 따로 본다. 대기열 진입과 조회의 503은 위 `queue busy` 규칙을 그대로 따른다.
- 진입이 응답 없이 끝나면(시간 초과, 연결 오류, 5xx) 서버에 진입이 커밋됐을 수 있어 상태 조회로 확인한다. 조회가 대기나 입장 허가를 돌려주면 그대로 이어 가고, 404면 다시 진입한다. `RETRY_MAX`와 관측 종료 안에서 확인하지 못하면 error로 끝낸다. 요약의 `enter without response` 줄과 `drop_queue_enter_unknown` 카운터(태그 `result`는 found, reentered, gave_up)에 나온다. 상태 확인 조회는 `eval poll`에, 404 뒤 다시 보낸 진입은 `eval enter`에 들어간다. `RETRY=0`이면 예전처럼 error로 끝낸다 (#421).
- 다른 드롭 시나리오(spike, step, capacity, queue-poll)는 재시도를 하지 않는다.
- on에서 생성기가 참여자만큼 VU를 만들지 못하면 `dropped_iterations`가 생긴다. 그 실행은 원래 조건을 만들지 못한 것이다.

### drop-reservation-capacity.js (용량 측정)

대기열을 끄고 점유 TTL을 실행 시간보다 길게 둔다(`RESTLESS_DROP_RESERVATION_TTL=PT30M`). 시드는 capacity다. SKU 1개 대조는 capacity_single을 쓴다.
워밍업과 측정은 별도 시나리오다. 워밍업은 `users.csv` 앞 `WARM_RATE` x `WARM` 행을 쓰고 측정은 그 뒤 행을 쓴다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `MODE` | ramp | ramp는 연속 증가 탐색, hold는 고정 도착률 유지 |
| `WARM_RATE`, `WARM` | 30, 60s | 워밍업 도착률과 시간. 판정에서 빼지만 계정과 재고는 쓴다 |
| `FROM`, `TO`, `RAMP` | 50, 350, 480s | ramp의 시작·끝 도착률과 오르는 시간 |
| `DOWN`, `TAIL_RATE`, `TAIL` | 30s, 30, 90s | ramp 뒤 내리는 시간, 회복 관측 도착률과 시간 |
| `RATE`, `HOLD` | 없음(필수), 180s | hold의 도착률과 유지 시간 |
| `WINDOW_SECONDS` | 30 | 창 길이 |
| `ABORT_ERROR_RATE` | 0.05 | 예상 밖 오류의 누적 비율이 넘으면 멈춘다. 30초 뒤부터 판정한다 |
| `SPARE_ACCOUNTS` | 50 | 스케줄링 오차에 대비해 더 읽는 계정 수 |

기본 ramp의 계획 도착 수는 106,200건이다. 요약의 창 표는 계획 수, 시작 수, 신규 점유 수, 평가 지연, 예상 밖 오류율을 보여 준다. 신규 성공률의 분모는 계획 수다.

```bash
-e MODE=ramp                    # 연속 증가로 후보 찾기
-e MODE=hold -e RATE=300        # 300건/초 3분 유지로 C 확인
```

### drop-queue-poll.js (대기열 폴링 측정)

대기열을 켜고 입장 작업을 끈다(`RESTLESS_DROP_QUEUE_SCHEDULER_ENABLED=false`). 시드는 대기열을 미리 채우지 않는다.
회원은 `ENTER_RATE`로 진입하고 진입하자마자 조회를 시작해 측정이 끝날 때까지 이어 간다. 구간은 fill, settle, warmup, measure 순서다.
대기자는 계정 순서대로 핫 SKU에 나뉘어 진입한다. 입장 순번은 SKU마다 따로 센다. `drop_queue_waiting`은 SKU를 합친 인원이다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `WAITERS` | 없음(필수) | 고정할 대기 인원. 절차는 100, 500, 1000, 2000, 5000, 10000 순서다 |
| `ENTER_RATE` | 50 | 초당 진입 인원 |
| `SETTLE`, `WARM`, `MEASURE` | 10s, 60s, 120s | 인원 안정화, 워밍업, 측정 시간 |
| `POLL_SECONDS` | 2 | 서버 응답에 `pollAfterMs`가 없을 때 쓰는 조회 간격. 있으면 그 값을 따른다 |

- 조회 응답의 `pollAfterMs`를 따르므로 새 서버에서는 초당 조회 수가 `WAITERS / POLL_SECONDS`보다 적다. 이전 서버와 비교할 때는 이 점을 조건에 적는다.
- 503 `DROP_QUEUE_BUSY`는 오류가 아니라 `Retry-After` 뒤 재시도로 세고 요약의 `queue busy` 줄과 busy 지연 줄에 나온다. 평가 지연에는 섞이지 않는다.
- 요약의 `fixed-N experiment held`는 모든 대기자가 측정 끝까지 WAITING이었다는 뜻이다. 한 명이라도 잃으면 `FAILED`다.
- 입장 작업이 켜져 있어 ADMITTED가 보이면 `drop_queue_unexpected_admission` 임계값이 실행을 멈춘다.
- 끝난 직후 서버의 `drop_queue_waiting` 값을 로그에 남긴다. 목표 인원과 같아야 한다.

### prom-collect.sh

```bash
perf/scenarios/prom-collect.sh http://perf-app:8081 .ai-output/perf/<실행>/prom 5 &
```

수집 시각(UTC)을 이름으로 한 `.prom` 파일을 남긴다. 실패한 수집은 `.failed` 파일로 표시한다. 네 번째 인자로 프로세스 ID를 주면 그 프로세스가 끝날 때 같이 끝난다.

### 로컬 확인 (2026-11-18, 측정 결과 아님)

스크립트가 도는지만 확인했다. 앱, MySQL, k6를 맥북 한 대에서 돌렸고 small 시드(재고 100, 계정 1,000)를 썼다. 지연은 기준선이 아니다.

| 실행 | 결과 | `--verify` |
|---|---|---|
| capacity hold, 10/s 6초 | 계획 80, 신규 점유 79, 오류 0 | 시드 기준값 외 0 |
| capacity ramp, 5→15/s | 계획 80, 신규 점유 79, 오류 0 | 시드 기준값 외 0 |
| sale off, 100/s | 결제 100, 품절 900 | 시드 기준값 외 0, 판매 수량 100 |
| sale on, 입장 100명/회 | 결제 100, 품절 900, 소비된 구매권 100 | 시드 기준값 외 0 |
| sale on, 입장 10명/회, 관측 12초 | 결제 100, 품절 113, 미완료 787 | 판매 수량 100, 승인 결제 100 대조 |
| queue poll, 200명 | 200명 유지, 측정 구간 조회 99.6/s, 서버 대기 인원 200 | 해당 없음 |
| queue poll, 입장 작업 켬 | 임계값으로 중단 | 해당 없음 |
| `HTTP_TIMEOUT=1ms` | 타임아웃 34건이 평가 지연에 1ms로 들어가고 응답 지연에는 0건 | 해당 없음 |
| sale off, sale_local 시드, 워밍업 50명 | 워밍업 드롭 결제 50, 판매 결제 50, 품절 450 | 시드 기준값 외 0, 핫 드롭 판매 50 |
| sale on, sale_local 시드, 워밍업 50명 | 워밍업 드롭 결제 50, 판매 결제 50, 품절 450, 소비된 구매권 50 | 시드 기준값 외 0 |

## 경매 몰림 부하 테스트: AU-1~AU-5

앱을 `perf` 프로필로 실행하고 `base` 시드를 측정 기간 시작에 한 번 넣습니다. 스크립트가 주최자로 로그인하고 필요한 판마다 새 경매를 만듭니다. 입찰자와 관전자는 기본으로 시드 토큰(`perf/seed/out/users.csv`, `AUTH_MODE=seed`)을 쓰고, `AUTH_MODE=login`이면 `LOGIN_RATE`로 로그인합니다. 토큰 받는 법은 [경매 부하 테스트 계획](../../docs/domains/auction/load-test.md#시드-토큰-받기)에 있습니다. 판마다 DB를 복원할 필요는 없습니다. 생성한 경매는 DB에 남으므로 독립적인 측정 기간을 시작할 때만 새 시드를 준비합니다.

Mac에는 k6 2.4.0(`brew install k6`)과 Node 24가 필요합니다. `PERF_APP_IP`에 대상 주소를 지정하면 실행기가 이를 `perf-local`에 매핑합니다. 실제 IP는 저장소나 결과에 기록하지 않습니다.

```bash
export PERF_APP_IP=<perf 서버 IP>
PHASES=200,300 perf/scenarios/auction-run.sh detail local-detail
PHASES=200,300 perf/scenarios/auction-run.sh list local-list
PHASES=200,300 perf/scenarios/auction-run.sh bid local-bid
PHASES=200,300 PHASE_GAP_SECONDS=240 perf/scenarios/auction-run.sh ending local-ending
PHASES=200 AUTH_MODE=login LOGIN_RATE=50 perf/scenarios/auction-run.sh bid local-bid-login
STEPS=50,100 ACCOUNTS=20 HOLD_SECONDS=20 perf/scenarios/auction-run.sh sse local-sse
```

기본 k6 실행은 한 번에 8,000건 판을 보낸 뒤 10,000건 판을 보냅니다. `PHASES`는 판 하나 또는 둘을 받고, 로컬 확인에는 요청 수를 줄일 수 있습니다. 실행기는 Actuator 수집을 시작하고 시나리오 종료 뒤 멈춥니다.

| ID | 실행 이름 | API / 인증 | 확인 목적 |
|---|---|---|---|
| AU-1 | `ending` | 입찰 POST, 시드 토큰 또는 로그인 | 마감 직전 입찰, 마감 전 발신 후 `AUCTION_CLOSED` 거절, 연장과 낙찰 정합성 |
| AU-2 | `detail` | 공개 상세 GET | 한 경매 상세 조회 몰림 |
| AU-3 | `list` | 공개 경매 목록 GET | OPEN 경매 목록 조회 몰림 |
| AU-4 | `bid` | 입찰 POST, 시드 토큰 또는 로그인 | 평시 입찰 몰림 |
| AU-5 | `sse` | 인증 SSE GET | 연결 유지와 변경 이벤트 전달 지연 |

AU-2·AU-3은 현재 공개된 GET이므로 토큰 없이 호출합니다. 401이면 공개 GET 경로가 막힌 것으로 기록합니다.

### k6 옵션

| 변수 | 기본값 | 설명 |
|---|---|---|
| `TARGET` | 실행기가 지정 | `ending`, `detail`, `list`, `bid` |
| `PHASES` | `8000,10000` | 오름차순 두 판의 요청 수 |
| `BIDDER_FIRST_ID`, `BIDDER_LAST_ALLOWED_ID` | `1`, `96900` | 연속 입찰자 ID 범위. 상한을 넘으면 중단 |
| `LOGIN_RATE`, `LOGIN_MARGIN_SECONDS` | `15`, `60` | 초당 로그인 수와 예상 로그인 완료 후 여유 시간 |
| `PHASE_GAP_SECONDS` | 일반 60, ending 240 | 판 사이 간격 |
| `AMOUNT_STEPS` | `100` | 시작가 위 최소 인상액 배수의 무작위 범위 |
| `ENDING_LEAD_MS` | `1000` | 판 시각부터 원래 종료까지의 시간 |
| `START_DELAY_SECONDS`, `AUCTION_DURATION_SECONDS` | `20`, `7200` | 경매 시작 지연과 일반 경매 길이 |
| `REQUEST_TIMEOUT_SECONDS`, `RESULT_WAIT_SECONDS` | `60`, `300` | 요청 제한 시간과 결과 조회 대기 한도 |
| `ORGANIZER_EMAIL`, `SEED_PASSWORD` | `organizer1@fanq.com`, `0000` | 시드 주최자 계정과 비밀번호 |

`ending`, `bid`, `detail`은 판마다 경매를 새로 만들고 `list`는 만들지 않습니다. 경매가 모두 OPEN 된 뒤 로그인 시작 시각과 판 시각을 정해 VU 로그인이 한꺼번에 밀리지 않게 합니다. 로그인은 setup 종료 뒤 10초 후 시작합니다. `ending`은 경매 등록에 종료 시각이 필요하므로 예정된 OPEN 시각, 로그인·여유 시간과 1초의 OPEN 전환 여유를 반영해 종료 시각을 먼저 잡습니다. 각 입찰자는 한 번 로그인하고 자신이 참여하는 각 판에서 한 번 요청합니다. 초당 15회로 10,000명을 로그인하면 약 11분이 걸리고, 첫 판 전에 설정한 여유 시간이 더해집니다.

### SSE 옵션

`auction-sse.mjs`는 Node 24 내장 fetch를 씁니다. 장시간 경매를 만들고 관전자 계정에 로그인한 뒤 토큰을 연결에 재사용합니다. 별도 측정 계정이 연결 단계마다 입찰합니다. 기본 누적 단계는 2,000, 4,000, 6,000, 8,000, 10,000입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `STEPS` | `2000,4000,6000,8000,10000` | 누적 SSE 연결 목표 |
| `ACCOUNTS`, `BIDDER_FIRST_ID`, `BIDDER_LAST_ALLOWED_ID` | `1000`, `1`, `96900` | 관전자 계정 수와 범위. 다음 두 ID는 번갈아 쓰는 측정 입찰자 |
| `LOGIN_RATE` | `15` | 초당 로그인 수. 관전자 1,000명은 약 67초 |
| `CONNECT_RATE` | `200` | 초당 새 SSE 연결 수 |
| `HOLD_SECONDS` | `60` | 각 연결 단계 유지 시간 |
| `CONNECT_WAIT_SECONDS`, `PROPAGATION_WAIT_SECONDS` | `30`, `15` | 첫 이벤트와 입찰 뒤 이벤트 대기 한도 |
| `POST_RUN_CLOSE_WAIT_SECONDS` | `90` | 마지막 단계 뒤 최종 CLOSED 이벤트 대기 시간. 60초 연장 포함 |
| `PERF_APP_IP` | 필수 | 환경 변수로만 지정하는 대상 IP |
| `PERF_APP_HOST`, `PERF_ALLOWED_APP_HOSTS` | `perf-local` | 논리 Host 이름과 허용 목록 |

각 단계는 시도·성공(HTTP 200 및 첫 이벤트)·상태별 실패·끊김·활성·변경 이벤트 수신 연결 수와 이벤트 지연 p50/p95/최대를 기록합니다. 최고 입찰자 연속 입찰은 거절되므로 관전자와 별도인 두 측정 계정이 번갈아 입찰합니다. 스크립트는 로그인·연결 계획에 입찰 연장 시간을 더해 경매 수명을 정하고 마지막 CLOSED 이벤트를 기다립니다. 연결이 남으면 클라이언트 연결을 닫고, 서버가 끊긴 emitter를 정리하도록 별도 정리 입찰을 보냅니다. 입찰 응답에는 revision이 없으므로 OPEN revision에 성공한 측정 입찰 수를 더해 기대 revision을 계산합니다.

### 결과와 해석

`auction-run.sh ending|detail|list|bid|sse [name]`은 `.ai-output/perf/auction/` 아래에 결과를 씁니다. JSON에는 조건, 판별 전송·응답·연결 실패, 상태·오류 코드 수, 지연, 입찰 대조 및 종료/SSE 결과가 들어갑니다. k6 실행은 HTML 대시보드도 만들고, 모든 실행은 로그와 2초 간격 Actuator CSV를 남깁니다.

각 판은 전송·응답·연결 실패·5xx·상태 코드, 지연 p50/p95/p99/최대, T부터 1초 안에 응답한 비율, 마지막 응답 시각, 전송 시각 퍼짐, 응답 시간 나누기(`timingMs`: 연결 차례 대기 `blocked`, TCP 연결 `connecting`, 첫 바이트까지 `waiting`)를 기록합니다. 입찰은 201 응답 수와 서버 수락 수를 대조합니다. 비영(非零) 5xx·연결 실패·입찰 수 차이·낙찰자 불일치·최종 종료 시각 뒤 수락(INV-AU-11)은 눈에 띄게 표시합니다. 원래 마감 뒤 수락은 연장 정책상 정상일 수 있습니다. 별도로 원래 마감 전에 보냈는데 `AUCTION_CLOSED`로 거절된 수를 기록합니다. 지연·처리량 합격선은 두지 않습니다.

CSV는 Hikari 활성·대기·타임아웃, Tomcat busy thread·현재 연결, 프로세스·시스템 CPU, `auction_stream_connections`, `http_server_requests_active_seconds_gcount` 합계, JVM 힙 사용·확보·최대(`area="heap"` 합계), GC 멈춤 시간 합과 횟수, JVM 스레드 수, 열린 파일 수를 샘플링합니다. 힙 열은 연결 수에 따른 메모리를 볼 때 씁니다. 컨테이너 전체 메모리는 Actuator에 없으므로 필요하면 EC2에서 `docker stats restless-perf`로 봅니다. 없는 지표나 응답은 빈 칸입니다.

### 안전과 정합성

앱 호스트는 `perf-`로 시작하고 `PERF_ALLOWED_APP_HOSTS`에 있어야 하며 `prod`를 포함하면 안 됩니다. 실행 전에 관리 endpoint의 `/actuator/prometheus`가 200인지 확인합니다. 비밀번호와 토큰은 로그나 요약에 남기지 않습니다.

측정 기간이 끝나면 필요에 따라 `perf/ec2/perf-ec2.sh verify base`를 한 번 실행해 불변식을 확인합니다. 부하로 데이터가 바뀌었으므로 seed baseline 일부는 달라질 수 있습니다. 종료된 경매는 revision에서 OPEN 전환 +1, 입찰당 +1, 종료 전환 +1을 반영해 수락 수를 `revision - 2`로 구합니다. 열린 입찰 경매는 `revision - 1`을 씁니다.

### 좌석 최초 조회 단계 계측 (perf 프로필)

`ticketing_seat_query_stage_seconds_{count,sum,max}`는 `stage`가 `performance`(회차 리포지토리), `layout_seats`(배치 리포지토리), `sale_seats`(판매 좌석 리포지토리), `combine`(배치 맵 구성·정렬·DTO 결합)인 호출 시간을 기록한다. `outcome`은 `success` 또는 `error`다. 리포지토리 호출 시간에는 DB 실행 외 커넥션 획득 대기·결과 매핑이 포함될 수 있으며 순수 SQL 시간이 아니다. 커넥션 획득은 기존 풀 전체 지표 `hikaricp_connections_acquire_seconds_{count,sum,max}`, `hikaricp_connections_pending`으로 본다. 획득 지표와 단계 시간은 겹칠 수 있고 풀 전체 값은 좌석 조회만의 값이 아니다. 트랜잭션 시작 시 획득하는 대기는 리포지토리 타이머 시작 전에 발생할 수도 있다. 단계 타이머의 max는 Micrometer의 시간 창 최대값이며 누적 최대가 아니다. perf 이외의 프로필에서는 단계 타이머가 생성되지 않는다.
