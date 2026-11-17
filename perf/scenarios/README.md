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
| [auction-bid-step.js](auction-bid-step.js) | AU-1·2·7 | 핫·분산·종료 직전 입찰 계단식 측정 |
| [auction-close-report.sql](auction-close-report.sql) | AU-8 | 동시 종료 지연과 결과 집계 |
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

## 경매: AU-1·2·7 계단식 입찰

`auction-bid-step.js`는 `MODE=hot`(AU-1), `spread`(AU-2), `ending`(AU-7)을 지원한다. 별도 조회 부하를 섞지 않도록 setup에서 대상의 최소 입찰가만 가져오며, 각 VU는 경매별 최소가를 기억한다. `BID_AMOUNT_TOO_LOW`는 새 키로 한 번 재시도한다. 409 업무 거절은 오류율에서 제외한다. 동일 계정도 재사용하며 `users.csv`의 1번부터 manifest `auction.mass_first_user_id` 직전까지만 사용한다. `ROW_OFFSET`은 앞의 회원 ID 수를 건너뛴다. AU-6·8용 계정은 사용하지 않는다.

**AU-7·8은 실행마다 DB를 새로 만들고 시드한 뒤 `AUCTION_CLOSE_IN_MINUTES`로 refresh한다.** 종료됐거나 추가 입찰이 생긴 경매는 refresh가 초기화하지 않는다. AU-7은 setup이 종료 `LEAD`초 전까지 기다리므로 종료 시각을 LEAD보다 충분히 뒤로 잡는다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `MODE` | `hot` | `hot`, `spread`, `ending` |
| `STEPS`, `STEP_RAMP`, `STEP_HOLD` | `10,25,50,100,200,400`, `10s`, `60s` | hot/spread 도착률과 계단 길이 |
| `LEAD`, `PEAK`, `HOLD` | `120`, `50`, `120s` | ending 시작 전 남은 초, 최대 도착률, 유지 시간 |
| `PRE_VUS`, `MAX_VUS`, `ABORT_ERROR_RATE` | `200`, `3000`, `0.05` | VU 수와 5xx·통신 오류의 누적 중단 비율 |
| `ROW_OFFSET` | `0` | 앞쪽에서 제외할 회원 ID 수 |
| `RESULT_WAIT_SECONDS`, `RESULT_POLL_SECONDS` | `600`, `3` | 종료 결과 조회 제한과 간격 |
| `SETUP_TIMEOUT`, `TEARDOWN_TIMEOUT` | `1h`, `15m` | 시작 대기·종료 조회의 k6 제한 시간 |
| `BASE_URL`, `MANAGEMENT_URL`, `PERF_ALLOWED_APP_HOSTS`, `SEED_OUT`, `SUMMARY_PATH` | 드롭과 동일 | 대상, 허용 호스트, 시드, JSON 결과 |

로컬과 EC2 대상 모두 노트북에서 실행한다. 로컬 앱 포트에 맞게 주소를 바꾸고, EC2 IP는 `--add-host perf-app:<EC2 IP>`로 지정한다. 아래 `MODE`를 `hot`, `spread`, `ending`으로 바꾼다.

```bash
mkdir -p .ai-output/perf
docker run --rm -v "$PWD/perf:/perf:ro" -v "$PWD/.ai-output/perf:/out" \
  --add-host perf-app:host-gateway \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SEED_OUT=/perf/seed/out \
  -e MODE=hot -e SUMMARY_PATH=/out/auction-hot.json \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT=/out/auction-hot.html \
  grafana/k6:2.4.0 run /perf/scenarios/auction-bid-step.js
```

모드별로 위 Docker 명령의 `-e MODE`와 출력 파일을 바꾼다:

| 실행 | 옵션 예시 |
|---|---|
| 로컬 AU-1 | `-e MODE=hot -e SUMMARY_PATH=/out/auction-hot.json` |
| 로컬 AU-2 | `-e MODE=spread -e SUMMARY_PATH=/out/auction-spread.json` |
| 로컬 AU-7 | `-e MODE=ending -e LEAD=120 -e SUMMARY_PATH=/out/auction-ending.json` |
| EC2 AU-1·2·7 | `--add-host perf-app:<EC2 IP>`와 위 각 MODE 옵션 |

EC2 실행은 위의 `--add-host` 주소를 EC2 IP로 바꾼다. EC2에서는 `perf/ec2/README.md`의 DB 생성·시드·토큰 전달과 앱 기동 절차를 따른다. `ending` 전에 앱을 멈춘 상태에서 서버의 perf 환경 변수로 `AUCTION_CLOSE_IN_MINUTES=4 perf/seed/seed.sh --scale small --profile perf --refresh`를 실행하고 토큰을 다시 전달한다.

계단별 결과는 입찰 요청 지연, 오류율과 초당 수락 건수다. 5xx·연결 오류만 누적 오류율 중단에 포함하고 수락·금액 부족·이미 최고·경매 종료·시작 전·크레딧 부족·기타 4xx를 따로 센다. 각 요청의 `retry` 태그는 재시도 여부를 나타낸다. `ending`은 종료 결과와 최초/최종 종료 시각, 연장 시간, 종료 처리 지연을 요약한다. 실행 후 `perf/seed/seed.sh --scale <scale> --profile perf --verify`에서 경매 불변식 10개가 0인지 본다. 시드 기준값 실패는 입찰 또는 종료 뒤 정상이다.

### 자기 준비 모드

서버를 직접 조작할 수 없는 측정자는 `SETUP=self`로 토큰 파일 없이 시작할 수 있다. 서버에서는 perf DB에 `seed base`를 **한 번** 넣고 `perf/ec2/perf-ec2.sh start`로 perf 앱을 띄워 둔다. 시드 회원과 주최자로 로그인한 뒤 판마다 새 경매를 등록한다. 서버 시각은 기존 시드 경매 1번의 상세 `serverTime`에서 읽는다. 요청은 모두 perf 호스트 허용 목록을 통과해야 한다.

```bash
mkdir -p .ai-output/perf
docker run --rm -v "$PWD/perf/scenarios:/scenarios:ro" -v "$PWD/.ai-output/perf:/out" \
  --add-host perf-app:<IP> \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SETUP=self -e MODE=hot \
  -e BIDDERS=1000 -e SUMMARY_PATH=/out/auction-self-hot.json \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT=/out/auction-self-hot.html \
  grafana/k6:2.4.0 run /scenarios/auction-bid-step.js
```

`MODE=spread`는 여러 경매를 만들고, `MODE=ending`은 종료 직전 경매를 만든다. 로그인과 등록은 setup에서 진행하므로 입찰 전 준비 시간이 필요하다. 로그인 성공률이 99% 미만이거나 경매가 열리지 않으면 측정을 시작하지 않는다. 각 실행이 만든 경매는 DB에 남아 누적된다. 여러 판이 끝나면 서버에서 `perf/ec2/perf-ec2.sh verify base`로 불변식을 확인한다. 시드 기준값 일부는 측정 후 실패하는 것이 정상이다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SETUP` | `manifest` | `self`면 직접 로그인과 등록, 기본값은 기존 CSV·manifest 방식 |
| `BIDDERS`, `BIDDER_FIRST_ID`, `BIDDER_LAST_ALLOWED_ID` | `1000`, `1`, `96900` | 연속 회원 ID 수·시작·허용 상한. small에서는 상한을 840으로 지정 |
| `ORGANIZER_EMAIL`, `SEED_PASSWORD` | `organizer1@fanq.com`, `0000` | 주최자와 시드 계정 비밀번호 |
| `START_DELAY`, `AUCTION_DURATION` | `20s`, `2h` | 등록부터 시작까지, hot/spread 경매 길이 |
| `SPREAD_COUNT`, `LOGIN_BATCH` | `100`, `50` | 분산 경매 수, 동시 로그인 요청 수 |

`BIDDER_LAST_ALLOWED_ID`는 base 시드의 AU-6·8 계정 보호 범위다. 다른 스케일에서는 해당 시드의 범위를 확인해 직접 설정한다. 시드 출력과 토큰을 측정자에게 복사할 필요는 없다. 요약 JSON에는 생성된 경매 ID, 로그인 요청·성공 수와 시간, 입찰자 범위와 주최자가 기록된다. 비밀번호와 토큰은 요약에 기록하지 않는다.

### AU-8 동시 종료 집계

새 DB를 시드하고 `AUCTION_CLOSE_IN_MINUTES=2 ... --refresh` 후 앱을 기동한다. 모두 종료된 뒤 manifest의 범위를 변수로 전달한다. 아래는 로컬에서 `seed.sh`와 같은 DB 환경 변수와 `MYSQL` 명령을 사용하는 예다. 비밀번호는 `MYSQL_PWD`로 전달한다.

```bash
export MYSQL_PWD="$DB_PASSWORD"
MYSQL=${MYSQL:-mysql}
read -r first last <<EOF_IDS
$(python3 -c 'import json; a=json.load(open("perf/seed/out/manifest.json"))["auction"]; print(a["mass_first_id"], a["mass_last_id"])')
EOF_IDS
{ printf 'SET @auction_mass_first=%s, @auction_mass_last=%s;\n' "$first" "$last"; cat perf/scenarios/auction-close-report.sql; } |
  $MYSQL -h "$DB_HOST" -P "${DB_PORT:-3306}" -u "$DB_USERNAME" "$DB_NAME"
```

`closed_count`, `open_count`, 지연 min/p50/p95/max(최근접 순위), 첫 종료부터 마지막 종료까지 초, 그 구간의 초당 종료 수, SOLD/UNSOLD와 HELD 수를 출력한다. 종료가 한 건뿐이면 처리 구간이 0초여서 초당 종료 수는 NULL이다. 다른 도메인의 스케줄 지연은 로그에서 따로 확인한다.

EC2에서는 `perf/ec2/perf-ec2.sh`에 SQL 실행 명령이 없으므로 서버에서 `/opt/restless/perf.env`의 DB 접속값을 안전하게 환경 변수로 설정한 뒤 위 명령을 실행한다. DB 이름이 `perf-`로 시작하는지 확인하고, EC2에 mysql 클라이언트가 없으면 `MYSQL='docker run --rm -i --network host -e MYSQL_PWD mysql:8.4 mysql'`을 쓴다. 자격 증명은 출력하거나 노트북으로 옮기지 않는다.
