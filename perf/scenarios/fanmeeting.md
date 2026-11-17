# 팬미팅 부하 시나리오

팬미팅 행사 하나로 오픈 응모 몰림 → 마감 → 추첨 → 결과 조회를 재는 스크립트다. 실행 환경과 원칙은 [perf/README](../README.md)를 따른다. 보통은 맨 위의 `fanmeeting-s1.sh` 하나만 쓴다.

| 파일 | 실험 | 내용 |
|---|---|---|
| [fanmeeting-prepare.sh](fanmeeting-prepare.sh), [fanmeeting-apply.js](fanmeeting-apply.js), [fanmeeting-check.sh](fanmeeting-check.sh) | 팬미팅 S1 (C46) | 응모 오픈 순간 서로 다른 회원의 첫 응모 몰림. 행사 생성 → 응모 → 점검 |
| [fanmeeting-result.js](fanmeeting-result.js) | 팬미팅 FM-EXP-05 | 추첨 확정 직후 응모자 전원의 결과·주문 조회 폭주. S1 행사에 이어서 |
| [fanmeeting-s1.sh](fanmeeting-s1.sh) | 팬미팅 S1 한 회 | **명령 하나로 행사 하나의 전체 흐름**: 지표 기록 → 행사 생성 → A(또는 B) → C → 추첨 대기(D) → 결과 조회 → 점검 → 요약 |
| [fanmeeting-metrics.sh](fanmeeting-metrics.sh) | 팬미팅 공통 | Grafana 없이 서버 지표 기록. 측정 내내 켜 두고 끝나면 구간별로 요약 |

## 팬미팅 S1 한 회를 명령 하나로 (`fanmeeting-s1.sh`)

행사 하나로 아래를 순서대로 돌고, 결과를 `RUN_DIR` 한 폴더에 모은다. 약 15~20분(오픈 대기 `OPEN_IN` + 응모 10분 + 추첨 + 조회). 측정한 사람은 **이 폴더를 통째로 넘기면 된다.** 토큰과 비밀번호는 폴더에 들어가지 않는다.

```text
지표 기록 시작 (fanmeeting-metrics.sh, 백그라운드)
→ 행사 생성 (fanmeeting-prepare.sh)
→ 응모 몰림 A: 오픈 + START_OFFSET부터 PARTICIPANTS명, PEAK_RATE/s  (START_OFFSET=-2s면 B)
→ 점검 1: 불변식, 회원당 1건, k6 201 = APPLIED
→ 마감 앞뒤 C: 마감 3초 전부터 안 쓴 계정 CLOSE_PARTICIPANTS명
→ 추첨 확정 대기 → 점검 2: 추첨 시간(D), 당첨·예비·주문 수
→ 결과 조회 폭주: A 참여자 전원, RESULT_RATE/s → 점검 3
→ 지표 기록 종료, 단계별 서버 지표 요약 → summary.txt
```

한 단계가 실패(임계값 실패, 점검 실패)해도 다음 단계로 가고, 끝에 실패한 단계를 모아 보여 주며 종료 코드 1로 끝난다. 행사 생성이 실패하면 바로 멈춘다.

### 실행

레포 루트에서. `perf-app`은 `/etc/hosts`에 EC2 주소로 등록한다(docker k6에는 그 주소를 자동으로 넘긴다).

```bash
export BASE_URL=http://perf-app:8080 MANAGEMENT_URL=http://perf-app:8081 PERF_ALLOWED_APP_HOSTS=perf-app
export SEED_OUT=perf/seed/out            # users.csv, staff.csv
# 점검까지 하려면 (없으면 점검만 건너뛰고 나중에 fanmeeting-check.sh로 한다)
export DB_HOST=<perf DB 호스트> DB_NAME=perf-restless DB_USERNAME=... DB_PASSWORD=... PERF_ALLOWED_DB_HOSTS=<perf DB 호스트>

PARTICIPANTS=1000 PEAK_RATE=200 perf/scenarios/fanmeeting-s1.sh
```

EC2에서 빌려 쓸 때는 [perf/ec2](../ec2/README.md) 순서를 따른다.

- 토큰은 `perf-ec2.sh seed base` → `serve-tokens`로 받는다. 묶음에 `users.csv`와 주최자 토큰 `staff.csv`가 같이 들어 있다.
- 노트북에서 RDS에 닿지 않으면 `DB_HOST` 없이 돌린다. 점검은 실행마다 EC2에서 `backend/src/test/resources/harness/fanmeeting-invariants.sql`을 perf DB에 실행해 모든 값이 0인지 본다.

### 변수 (나머지는 각 스크립트와 같음)

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `PARTICIPANTS`, `PEAK_RATE`, `RAMP`, `HOLD` | 1000, 1000, 5s, 자동 | A의 참여자와 도착률. `HOLD`를 비우면 모든 참여자가 보낼 만큼 자동으로 잡는다 |
| `START_OFFSET` | `2s` | A는 `2s`, B는 `-2s`. 음수면 단계 이름이 `apply-B`가 된다 |
| `CLOSE_PARTICIPANTS`, `CLOSE_RATE` | 500, 자동 | C. A가 쓰지 않은 계정(`USER_OFFSET=PARTICIPANTS`)을 쓴다. 계정이 모자라면 남는 만큼만, 0이면 건너뛴다. 도착률을 비우면 마감 3초 전부터 약 6초에 걸쳐 보내도록(참여자/6) 정한다 |
| `RESULT`, `RESULT_RATE`, `RESULT_HOLD` | `true`, `PEAK_RATE`, 자동 | 결과 조회 폭주. A 참여자만 조회한다. C가 있으면 DB의 당첨·예비·주문 수에는 C 응모자도 들어가므로 첫 바퀴 수가 DB보다 작다 |
| `DRAW_TIMEOUT` | 600 | 마감 뒤 추첨 확정을 기다리는 최대 초. 넘으면 조회를 건너뛴다 |
| `METRICS_INTERVAL` | 2 | 지표 저장 간격(초). 한 회 약 100MB(스냅샷 약 160KB × 600장). 보낼 때는 압축한다 |
| `OPEN_IN`, `WINNER_LIMIT`, `RESERVE_LIMIT`, `FEE_AMOUNT` | prepare와 같음 | 행사 설정 |
| `K6`, `K6_IMAGE`, `K6_ADD_HOST` | `docker`, `grafana/k6:2.4.0`, 자동 | `K6=local`이면 설치된 k6. `K6_ADD_HOST`는 컨테이너 안에서 `perf-app`이 가리킬 주소(로컬 실행이면 `host-gateway`) |
| `INVARIANTS_SQL` | `backend/src/test/resources/harness/fanmeeting-invariants.sql` | DB 점검을 할 때만 필요 |
| `RUN_DIR` | `.ai-output/perf/s1-<월일-시각>-p<참여자>-r<도착률>` | 결과 폴더. 이미 있으면 시작하지 않는다 |

### 결과 폴더

| 파일 | 내용 |
|---|---|
| `summary.txt` | **한 장 요약.** 조건, 단계별 시각, A·B·C 결과(시간 구간별), 조회 첫 바퀴 분포, 단계별 서버 p95·p99·처리량·Hikari·CPU, 점검 핵심 줄 |
| `conditions.txt`, `timeline.tsv`, `event.env` | 조건, 단계별 시작·끝(UTC)·종료 코드, 행사 |
| `apply-A.json`(`apply-B`), `apply-C.json`, `result.json` | k6 요약 |
| `check-1-apply.txt`, `check-2-draw.txt`, `check-3-result.txt` | 점검 출력 |
| `metrics/`, `metrics-<단계>.txt/json`, `metrics-all.txt` | 서버 지표 스냅샷과 단계별·전체 요약 |
| `run.log` | 화면 출력 전체 |

같은 시간대의 EC2 CPU 크레딧(CloudWatch)과 RDS CPU는 `timeline.tsv`의 시각으로 따로 본다.

### 측정 순서 제안 (한 회 = 행사 하나 = 약 15~20분)

| 회차 | 명령 | 목적 |
|---|---|---|
| 1 | `PARTICIPANTS=1000 PEAK_RATE=200` | 환경 확인 |
| 2 | `PARTICIPANTS=10000 PEAK_RATE=500` | 한계 찾기 시작 |
| 3 | `PARTICIPANTS=10000 PEAK_RATE=1000` | 〃. BUSY·p99·Hikari pending이 오르기 시작하면 그 직전이 한계 |
| 4 | `PARTICIPANTS=10000 PEAK_RATE=2000` | 〃 (3에서 이미 무너졌으면 생략) |
| 5 | `PARTICIPANTS=100000 PEAK_RATE=<한계의 70~80%> CLOSE_PARTICIPANTS=0` | 10만 응모, 추첨(D), 조회. 계정을 A가 다 쓰므로 C는 없음 |
| 6 | `PARTICIPANTS=10000 PEAK_RATE=1000 START_OFFSET=-2s` | B: 오픈 직후 거절 수 |

- 회차 사이에는 다른 측정과 겹치지 않게 한다. 한 회 안에서 행사는 하나만 연다.
- 노트북이 먼저 막혔는지 `summary.txt`의 `dropped`와 k6 요약의 `http_req_connecting`으로 확인한다([기준](../README.md#부하-발생기를-ec2로-옮기는-기준)).

## 팬미팅 서버 지표 기록 (`fanmeeting-metrics.sh`)

Grafana가 없어도 서버 쪽 숫자를 남긴다. 관리 포트의 `/actuator/prometheus`를 `INTERVAL`초마다 파일로 저장하고, 끝나면 구간 차이로 계산한다. 앱에는 읽기 요청만 보낸다. `curl`과 `python3`만 있으면 된다.

**측정 시작 전에 켜고, 모든 실행(A·B·C·조회)이 끝난 뒤 Ctrl-C로 끈다.** 여러 실행이 한 기록에 섞이므로, 실행마다 k6가 출력한 시작·끝 시각으로 `summary`를 다시 잘라 본다.

```bash
# 터미널 하나에서 측정 내내
MANAGEMENT_URL=http://perf-app:8081 PERF_ALLOWED_APP_HOSTS=perf-app \
  OUT_DIR=.ai-output/perf/metrics-s1 perf/scenarios/fanmeeting-metrics.sh

# 끝난 뒤 실행 하나만 (ISO-8601 또는 epoch ms)
FROM=2026-11-17T05:03:00Z UNTIL=2026-11-17T05:04:00Z \
  perf/scenarios/fanmeeting-metrics.sh summary .ai-output/perf/metrics-s1
```

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `MANAGEMENT_URL`, `PERF_ALLOWED_APP_HOSTS` | `http://perf-app:8081` / 없음 (필수) | k6와 같은 호스트 규칙. perf-로 시작하고 허용 목록에 있고 prod가 아니어야 한다 |
| `INTERVAL` | 5 | 저장 간격(초). 짧은 스파이크를 보려면 2 |
| `DURATION` | 0 | 0이면 Ctrl-C까지, 아니면 그 초만큼 |
| `OUT_DIR` | `.ai-output/perf/metrics-<시각>` | 스냅샷(`<epoch ms>.prom`)과 `summary.json` |
| `PERF_APP_ADDR` | 없음 | `/etc/hosts` 대신 perf- 이름을 이 IP로 연결 |
| `FROM`, `UNTIL` | 없음 | `summary`에서 볼 구간 |

요약에 나오는 것:

| 항목 | 뜻 |
|---|---|
| 엔드포인트별 `requests`, 상태 코드별 수 | 서버가 실제로 받은 요청. k6 수와 같아야 한다 |
| `avg`, `peak` (/s) | 구간 평균, 스냅샷 사이 최고 처리량. **한계 TPS는 `peak`가 더 오르지 않고 p99와 BUSY가 오르기 시작하는 지점**이다 |
| `p50`, `p95`, `p99`, `max` | 서버 처리 시간(히스토그램 구간 보간). 네트워크 왕복이 없는 값이다 |
| hikari `active/max`, `pending`, `timeouts`, `acquire max` | 연결 풀. active가 max에 붙고 pending이 쌓이면 연결 풀이 병목이다 |
| tomcat `busy`, cpu `process`/`system`, heap | 요청 스레드, CPU, 메모리 |
| fanmeeting workers | Worker 실행 수, 오류, 평균·최대 시간. 열기·마감 지연을 볼 때 `run`(열기·마감)을 본다 |

- `max`, `pending` 같은 순간값은 스냅샷 시각의 값이라 `INTERVAL`보다 짧은 스파이크는 놓칠 수 있다.
- 기록 중 앱이 재시작되면 경고가 나온다. 그 구간 숫자는 쓰지 않는다.
- EC2 CPU 크레딧과 RDS CPU는 AWS 콘솔(CloudWatch, RDS 모니터링)에서 같은 시간대로 본다.

## 팬미팅 S1: 응모 몰림 (C46)

> 한 회를 손으로 나눠 돌리지 않으려면 [`fanmeeting-s1.sh`](#팬미팅-s1-한-회를-명령-하나로-fanmeeting-s1sh)를 쓴다. 아래는 각 스크립트를 따로 돌릴 때의 설명이다.

[C46](../../docs/domains/fanmeeting/test-scenarios.md)을 부하로 잰다. `POST /api/fan-meetings/{eventId}/applications`만 친다. 추첨은 마감 뒤 앱의 Draw Worker가 돌리고, 점검 스크립트가 그 시간을 읽는다. 결제는 재지 않는다.

| 시나리오 | 보는 것 | 실행 |
|---|---|---|
| A | 오픈 순간 몰림. 응모 수 = 201 수, `APPLICATION_BUSY` 0, 불변식 0 | `START_OFFSET=2s` |
| B | 오픈 직전부터 보낼 때 Open Worker 지연. 오픈 뒤 몇 초 동안 `APPLICATION_WINDOW_CLOSED`가 나는지 | `START_OFFSET=-2s` |
| C | 마감 앞뒤 요청. 마감 1초 뒤부터 보낸 요청의 201이 0인지 | `MODE=close` |
| D | 마감 뒤 추첨 시간. `started_at → completed_at`, `attempt_count`, `candidate_count` | 마감 뒤 `fanmeeting-check.sh` |

- 응모 기간은 10분 고정이다([정책 3절](../../docs/domains/fanmeeting/policy.md)). 행사 하나로 A나 B를 한 번, 같은 행사 마감에 C를 한 번 돌릴 수 있다. 측정마다 새 행사를 만든다.
- 응모는 Open Worker(1초 간격)가 행사를 `OPEN`으로 바꾼 뒤부터 받는다. 오픈 시각 정각에 보내면 첫 1초 안의 요청 일부가 `APPLICATION_WINDOW_CLOSED`를 받는다. 이 값은 B에서 재고, A는 `START_OFFSET=2s`로 Worker 뒤에 시작한다.
- 한 계정은 한 번만 보내고 거절돼도 다시 보내지 않는다. B와 C에서는 201 수가 참여자보다 적은 것이 정상이다.
- 측정하는 동안 다른 도메인 Worker는 그대로 돈다. 다른 도메인 시드의 Worker 작업이 섞이면 결과에 적는다.

### 실행 순서

레포 루트에서 실행한다. `perf-app`은 [perf/README](../README.md#실행-환경)처럼 `/etc/hosts`에 EC2 주소로 등록한다.

1. 시드: `perf/seed/seed.sh --scale base --profile perf` (스냅샷 복원 뒤면 `--refresh`). 회원 토큰 `users.csv`, 주최자 토큰 `staff.csv`를 쓴다.
2. 행사 만들기: 오픈까지 `OPEN_IN`(기본 3분)이 남은 행사를 만들고 `.ai-output/perf/fanmeeting-event.env`에 ID와 시각을 남긴다.

```bash
export BASE_URL=http://perf-app:8080 MANAGEMENT_URL=http://perf-app:8081 PERF_ALLOWED_APP_HOSTS=perf-app
perf/scenarios/fanmeeting-prepare.sh
```

3. k6: 오픈 전에 시작한다. `setup`이 시작 시각까지 기다렸다가 부하를 건다.

```bash
docker run --rm -v "$PWD/perf:/perf:ro" -v "$PWD/.ai-output/perf:/out" \
  --add-host perf-app:10.0.1.10 \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SEED_OUT=/perf/seed/out \
  -e EVENT_FILE=/out/fanmeeting-event.env -e PARTICIPANTS=10000 -e START_OFFSET=2s \
  -e SUMMARY_PATH=/out/fanmeeting-apply.json \
  grafana/k6:2.4.0 run /perf/scenarios/fanmeeting-apply.js
```

4. 점검: 응모가 끝나면 한 번, 마감(오픈 + 10분) 뒤 추첨이 끝나면 한 번 더 실행한다.

```bash
export DB_HOST=<perf DB 호스트> DB_NAME=perf-restless DB_USERNAME=... DB_PASSWORD=... PERF_ALLOWED_DB_HOSTS=<perf DB 호스트>
INVARIANTS_SQL=backend/src/test/resources/harness/fanmeeting-invariants.sql \
EVENT_FILE=.ai-output/perf/fanmeeting-event.env SUMMARY_PATH=.ai-output/perf/fanmeeting-apply.json \
perf/scenarios/fanmeeting-check.sh
```

### 변수

`fanmeeting-prepare.sh`

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL`, `MANAGEMENT_URL`, `PERF_ALLOWED_APP_HOSTS` | perf-app 8080, 8081 / 없음 (필수) | k6와 같은 값 |
| `SEED_OUT` | `perf/seed/out` | `staff.csv` 위치. 첫 ORGANIZER 행의 토큰을 쓴다 |
| `OPEN_IN` | `3m` | 지금부터 응모 오픈까지. `90s`, `3m`, `1h`. 30초 이상 |
| `WINNER_LIMIT`, `RESERVE_LIMIT`, `FEE_AMOUNT` | 300, 100, 50000 | 행사 설정 |
| `TITLE`, `OPENS_AT_PUBLIC` | `perf S1 응모 <월일-시각>`, `true` | 제목, 예고 오픈 여부 |
| `EVENT_FILE` | `.ai-output/perf/fanmeeting-event.env` | 출력 파일 |
| `PERF_APP_ADDR` | 없음 | `/etc/hosts` 대신 perf- 이름을 이 IP로 연결한다. 로컬 실행이면 `127.0.0.1` |

시각은 정책 3절을 만족하게 계산한다. `firstNoticeScheduledAt` = 지금, `entryOpensAt` = 지금 + `OPEN_IN`, 마감은 서버가 오픈 + 10분으로 정한다. `meetingAt` = 마감 + 72시간, `promotionClosesAt`은 비워서 기본값(`meetingAt` - 24시간)을 쓴다.

`fanmeeting-apply.js`

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL`, `MANAGEMENT_URL`, `PERF_ALLOWED_APP_HOSTS`, `SEED_OUT` | 드롭과 같음 | 드롭 절 참고 |
| `EVENT_FILE` | 없음 | prepare가 만든 파일. 또는 `EVENT_ID`, `ENTRY_OPENS_AT`, `ENTRY_CLOSES_AT`를 직접 준다 |
| `MODE` | `open` | `open`이면 기준 시각이 오픈(A·B), `close`면 마감(C) |
| `START_OFFSET` | open `0s`, close `-3s` | 기준 시각에서 부하 시작까지. `-2s`, `500ms`, `2s` |
| `START_AT` | 기준 시각 + `START_OFFSET` | 시작 시각을 ISO-8601로 직접 줄 때 |
| `PARTICIPANTS`, `USER_OFFSET` | 1000, 0 | `users.csv`의 USER 행 중 `USER_OFFSET` 다음부터 쓰는 계정 수. C는 같은 행사에 응모하지 않은 계정을 쓰도록 `USER_OFFSET`을 준다 |
| `PEAK_RATE`, `RAMP`, `HOLD` | 1000, 5s, 20s | 최고 도착률(초당 반복), 오르는 시간, 유지 시간 |
| `PRE_VUS`, `MAX_VUS` | 500, 3000 | 미리 만들 VU, 최대 VU |
| `SUMMARY_PATH` | 없음 | JSON 요약 경로 |

총 도착 수(대략 `PEAK_RATE` x (`RAMP`/2 + `HOLD` + 2.5초))가 참여자보다 많아야 모든 계정이 요청한다. 10만 명이면 `PEAK_RATE=5000`처럼 올리거나 `HOLD`를 늘린다. 남는 반복은 `skipped_no_account`로 센다.

`fanmeeting-check.sh`

| 변수 | 뜻 |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `PERF_ALLOWED_DB_HOSTS`, `MYSQL` | `seed.sh`와 같음 |
| `INVARIANTS_SQL` | 불변식 SQL. `-- name:` 블록마다 위반 행 수를 낸다. DB 전체를 검사한다 |
| `EVENT_FILE` 또는 `EVENT_ID` | 응모 정합성과 추첨을 볼 행사 |
| `SUMMARY_PATH` | 있으면 k6 201 수와 APPLIED 수를 비교한다 |

불변식 위반이 있거나, 회원당 응모가 2건 이상이거나, 201 수와 APPLIED 수가 다르면 종료 코드 1이다.

### 안전장치

- prepare와 k6: `BASE_URL`과 `MANAGEMENT_URL`의 호스트가 `perf-`로 시작하지 않거나, `PERF_ALLOWED_APP_HOSTS`에 정확히 없거나, 이름에 prod가 들어 있으면 시작하지 않는다. 관리 포트의 `/actuator/prometheus`가 200이 아니면 perf 앱이 아니므로 멈춘다.
- k6: 행사가 `SCHEDULED`나 `OPEN`이 아니면(C는 `OPEN`이 아니면) 멈춘다. 시작 시각이 30분 넘게 남았으면 시작하지 않는다. `FAN_MEETING_NOT_FOUND`나 401이 나오면 다음 임계값 판정 때 중단한다. 행사 ID나 토큰(`JWT_SECRET`)이 틀렸다는 뜻이다.
- check: `seed.sh`와 같다. DB 호스트가 허용 목록에 없거나, DB 이름이 `perf-`로 시작하지 않거나, 호스트나 DB 이름에 prod가 있으면 멈춘다. 비밀번호는 `MYSQL_PWD` 환경 변수로만 넘긴다.
- 토큰은 화면과 파일에 남기지 않는다.

### 결과 해석

| 지표 | 응답 | 기대 |
|---|---|---|
| `fm_applied` | 201 | A: 참여자 수. 점검의 APPLIED 수와 같아야 한다 |
| `fm_already_applied` | 409 `ALREADY_APPLIED` | 0. 계정을 한 번만 쓰므로 나오면 조사한다 (임계값) |
| `fm_window_closed` | 409 `APPLICATION_WINDOW_CLOSED` | A: 0. B·C: 구간별로 본다 |
| `fm_busy` | 503 `APPLICATION_BUSY` | 0. C46 기준. 0이 아니면 건수와 서버 로그의 원인을 적는다 |
| `fm_not_found`, `fm_unauthorized` | 404, 401 | 0. 나오면 바로 멈춘다 |
| `fm_other_client_error`, `fm_server_error`, `fm_transport_error` | 그 밖의 4xx, 5xx, 연결 오류와 타임아웃 | 0 |

- 요청 합계(`requests total`)가 참여자 수와 같아야 모든 계정이 보낸 것이다.
- 구간 집계(`fm_applied_*`, `fm_window_closed_*`)는 기준 시각(오픈 또는 마감)에서 요청을 보낸 시각까지 경과 시간이다. `before`, `0_1s`, `1_2s`, `after_2s`로 나눈다. 노트북 시계 기준이고, 서버 시계와의 차이를 `serverClockOffsetMs`(Date 헤더라 1초 단위)로 남긴다.
- B: `fm_window_closed_0_1s`, `1_2s`가 Open Worker 지연이다. `before`는 오픈 전이라 거절되는 것이 정상이다.
- C: 마감 판정은 커밋 기준이다. 마감 직전에 보낸 요청은 마감 뒤에 커밋돼도 성공할 수 있다. 그래서 `fm_applied_0_1s`는 허용하고 `fm_applied_1_2s`, `fm_applied_after_2s`가 0인지 본다(임계값). 0이 아니면 시계 차이부터 확인한다.
- D: 마감 뒤 Draw Worker(5초 간격)가 추첨한다. 점검 스크립트의 `closed→completed`가 마감에서 결과 확정까지, `started→completed`가 추첨 계산과 저장 시간이다. `attempt_count`가 1보다 크면 재시도한 것이니 서버 로그를 본다. `candidate_count`는 APPLIED 수와 같아야 한다(탈퇴 회원이 없을 때).
- k6 지연(`fm_apply_duration`)은 집 네트워크 왕복이 섞인 체감값이다. 서버 지연은 관리 포트의 `http_server_requests_seconds{uri="/api/fan-meetings/{eventId}/applications"}`로 본다.
- 요약에 `dropped_iterations`, `http_req_connecting`, `http_req_blocked`를 남긴다. 노트북이 병목인지는 [부하 발생기를 EC2로 옮기는 기준](../README.md#부하-발생기를-ec2로-옮기는-기준)으로 판단한다.
- 불변식 SQL은 `backend/src/test/resources/harness/fanmeeting-invariants.sql`이다. 테스트와 같은 파일을 쓴다.

## 팬미팅 결과 조회 폭주 (FM-EXP-05)

추첨이 확정되면 응모자 대부분이 동시에 결과를 새로고침한다. S1에서 응모한 **같은 행사, 같은 회원**으로 이어서 잰다. 읽기만 하므로 DB에 데이터를 남기지 않는다.

- 반복 1번 = 회원 1명의 새로고침 1번이다. `GET .../result/me`를 부르고, 결과에 `orderId`가 있으면(당첨, 승격된 예비) `GET .../orders/me`도 부른다.
- 처음 `PARTICIPANTS`번 반복(첫 바퀴)은 회원마다 정확히 한 번이다. 이 결과 분포를 점검 스크립트의 DB 숫자와 비교한다. 그 뒤 반복은 같은 회원의 새로고침이다.
- 결제 시작 API와 섞어 서로 방해하는지는 이번에 재지 않는다(결제 담당 시나리오와 같이 정한다).

### 실행

S1의 D(추첨)가 끝난 뒤, 즉 `fanmeeting-check.sh`에 DrawRun 완료가 보인 뒤 실행한다. `PARTICIPANTS`와 `USER_OFFSET`은 그 행사에 응모한 apply 실행과 같게 준다.

```bash
docker run --rm -v "$PWD/perf:/perf:ro" -v "$PWD/.ai-output/perf:/out" \
  --add-host perf-app:10.0.1.10 \
  -e BASE_URL=http://perf-app:8080 -e MANAGEMENT_URL=http://perf-app:8081 \
  -e PERF_ALLOWED_APP_HOSTS=perf-app -e SEED_OUT=/perf/seed/out \
  -e EVENT_FILE=/out/fanmeeting-event.env -e PARTICIPANTS=10000 \
  -e PEAK_RATE=1000 -e HOLD=60s -e SUMMARY_PATH=/out/fanmeeting-result.json \
  grafana/k6:2.4.0 run /perf/scenarios/fanmeeting-result.js
```

### 변수

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL`, `MANAGEMENT_URL`, `PERF_ALLOWED_APP_HOSTS`, `SEED_OUT`, `EVENT_FILE`/`EVENT_ID` | apply와 같음 | |
| `PARTICIPANTS`, `USER_OFFSET` | 1000, 0 | 그 행사에 응모한 apply 실행과 같은 값 |
| `PEAK_RATE`, `RAMP`, `HOLD` | 1000, 5s, 60s | 초당 새로고침 수, 오르는 시간, 유지 시간. 총 반복이 `PARTICIPANTS` 이상이어야 첫 바퀴가 끝난다 |
| `PRE_VUS`, `MAX_VUS` | 300, 2000 | |
| `REQUIRE_DRAWN` | `true` | 행사가 `PAYMENT_IN_PROGRESS`나 `RECRUITMENT_COMPLETED`가 아니면 멈춘다. `false`면 추첨 확정 전에도 돈다(그때 `PENDING`이 정상) |
| `SUMMARY_PATH` | 없음 | JSON 요약 경로 |

### 결과 해석

| 지표 | 기대 |
|---|---|
| `fm_result_first_winner`, `_reserve`, `_not_selected` | 점검의 `selections WINNER`, `RESERVE`와 같다. 미선발 = 참여자 − 당첨 − 예비 |
| `fm_result_first_with_order` | 점검의 `orders` 수와 같다 |
| `fm_result_first_pending` | 0. 확정 뒤인데 `PENDING`이면 결과 공개가 늦은 것이다 (임계값) |
| `fm_result_first_not_applied` | 0. 나오면 `PARTICIPANTS`·`USER_OFFSET`이 apply와 다르다 (임계값) |
| `fm_order_not_found`, `fm_order_mismatch` | 0. 결과에는 주문이 있는데 주문 조회가 없다고 하거나 번호가 다르면 두 조회가 다른 상태를 본다 (임계값) |
| `fm_server_error`, `fm_transport_error`, `fm_other_client_error` | 0 |
| 첫 바퀴 `total` | 참여자 수. 모자라면 `HOLD`나 `PEAK_RATE`를 늘린다 |

- 서버 지연은 관리 포트의 `http_server_requests_seconds{uri="/api/fan-meetings/{eventId}/result/me"}`, `.../orders/me`로 본다. 같이 볼 것: Hikari 활성·대기 연결, DB CPU, 요청당 쿼리 수(슬로우 쿼리 로그나 `performance_schema`).
- k6 지연(`fm_read_duration{endpoint:result|order}`)은 체감값이다.
