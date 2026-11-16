# k6 시나리오

[성능 실험 계획](../../docs/testing/performance.md)의 실험을 돌리는 k6 스크립트다. 입력 데이터는 [시드](../seed/README.md)가 만든다.
드롭, 주문, 결제 시나리오만 둔다. 다른 도메인 시나리오는 각 담당자가 준비한다.

| 파일 | 실험 | 내용 |
|---|---|---|
| [drop-reservation-spike.js](drop-reservation-spike.js) | EXP-01 쓰기 기준선 | 판매 시작 순간 핫 드롭 재고 점유 스파이크. 대기열 없음 |
| [drop-reservation-step.js](drop-reservation-step.js) | EXP-01 용량 측정 | 도착률을 계단식으로 올려 무릎 지점과 최대 지속 처리량을 찾는다. 대기열 없음 |

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
