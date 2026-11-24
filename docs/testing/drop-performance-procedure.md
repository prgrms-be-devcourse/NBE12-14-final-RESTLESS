# 드롭 성능 측정 실행 절차

[계획과 진행 방식](drop-performance-plan.md) / [측정 규약](drop-performance-measurement.md)

측정 한 번을 돌리는 방법이다. 무엇을 언제 잴지는 계획 문서 2절의 반복이 정한다. 서버 두 대를 ALB 뒤에 두는 측정, DB 프로브, JFR은 7절에 있다.

## 1. 환경과 설정

앱 EC2 한 대, 기존 RDS, 로컬 k6를 쓴다. 서버 두 대 측정은 7절을 따른다. 같은 시간 다른 팀의 공유 자원 부하를 피한다. 테스트는 perf 앱과 별도 `perf-` DB를 쓰고, 발표는 prod 설정을 쓴다. 실제 PG에는 부하를 주지 않는다. 가짜 PG 지연은 300ms이고 거절, unknown, 유실 비율은 0이다.

| 측정 | 시드 | queue.required | 점유 TTL | 입장 허가 작업 | 풀 |
|---|---|---|---|---|---|
| 용량 측정 | `capacity` (SKU 3개). 잠금 모델 대조는 `capacity_single` (SKU 1개) | false | PT30M | 기본 유지 | 비교 변인이 아니면 고정 |
| 대표 판매 측정 | `sale` (재고 1,000과 워밍업 드롭) | false / true | PT5M | 켬은 정한 `admit-per-run`, fixedDelay 1초 | 고정 |
| 대기열 폴링 측정 | `base`, 대기열 미리 채움 0 | true | PT5M | `scheduler.enabled=false` | 고정 |

비밀값 없는 실행 기록에 이미지 digest와 커밋, 시드와 스크립트 해시, DB 행 수와 SKU 재고 분배, EC2와 RDS 사양, CPU 크레딧, JVM, Tomcat, Hikari, PG, TTL, 스케줄러 설정, UTC를 남긴다. 전후 비교 중에는 바꾸는 하나 말고는 고정한다.

`perf.env`(root 소유 600)는 권한 있는 편집기로 바꾸고 비밀값은 출력하지 않는다. 자주 바꾸는 키:

- `RESTLESS_DROP_QUEUE_REQUIRED`, `RESTLESS_DROP_RESERVATION_TTL`
- `RESTLESS_DROP_QUEUE_ADMIT_PER_RUN`, `RESTLESS_DROP_QUEUE_SCHEDULER_ENABLED`, `RESTLESS_DROP_QUEUE_SCHEDULER_FIXED_DELAY_MS`
- `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`

유효 설정은 앱을 다시 만들고 런타임 지표로 확인한다. `check`가 풀 크기를 검증한다고 가정하지 않는다. [EC2 도구](../../perf/ec2/README.md)의 `start`는 자동 배포를 멈추고 perf 컨테이너를 만든다. `finish`는 perf 컨테이너를 지우고 자동 배포를 재개하므로 최신 `:dev`가 뜰 수 있다. 측정 이미지와 발표 이미지를 구분한다.

## 2. 실행 순서

실행마다 결과 폴더 `.ai-output/perf/<날짜>-<측정>/`를 하나 만들고 그 안에만 저장한다. 이 폴더는 커밋하지 않는다. k6는 한 번에 하나만 돌린다.

1. 이전 실행의 원자료와 `verify` 출력을 그 실행 폴더에 저장한다.
2. `stop-app` → `reset-db` → 같은 이미지로 `start` (Flyway) → `stop-app` → `seed`. `refresh`는 초기화가 아니다.
3. `serve-tokens`로 새 토큰 CSV와 실행 기록을 노트북에 받는다.
4. `start` 뒤 `lockstats`로 행 잠금 누적값을 남긴다. 이 값은 RDS 재시작 때만 0이 되므로 실행 전후 차이로 본다.
5. 노트북에서 `prom-collect.sh`를 시작한다. 노트북 `~/.curlrc`에 `ipv4` 줄이 있는지 먼저 본다.
6. 변경 전 예측을 결과 폴더에 한 줄 적는다.
7. k6를 한 번 돌린다. 요약은 터미널을 지우기 전에 저장한다.
8. 수집을 멈추고 `after-run.prom`을 받는다. `lockstats`를 다시 남긴다.
9. 진행 중 거래가 수렴했는지 보고 `verify`를 돌린다. 불확실한 거래를 임의 실패로 닫지 않는다.

워밍업:

- 용량 측정은 30건/초로 60초 워밍업한다. 첫 30초와 뒤 30초의 서버 처리, 커넥션 사용, CPU가 10% 넘게 다르면 워밍업을 늘리고 길이를 고정한다.
- 대표 판매 측정은 핫 재고를 쓰지 않는 별도 워밍업 드롭과 계정을 쓴다. `sale` 계열 시드가 워밍업 드롭을 만들고 `drop-sale.js`가 판매 전에 그 드롭으로 보낸다. 워밍업 뒤 핫 드롭 재고 1,000, 활성 거래 0을 확인한다. 워밍업을 위해 측정 직전 앱을 재시작하지 않는다.

## 3. 용량 측정

`drop-reservation-capacity.js`를 쓴다. 성공 점유 경로를 보는 합성 데이터이며 대표 시나리오의 재고를 늘리는 것이 아니다.

- **후보 찾기 (연속 증가):** 워밍업 뒤 50에서 350건/초로 480초 올리고, 30초에 걸쳐 30건/초로 내린 뒤 90초 관측한다. 계획 도착은 106,200건이다. 30초 창별 지연과 서버 지표로 포화 시작점과 먼저 포화한 자원을 찾는다. 판정에는 쓰지 않는다.
- **C 확인 (고정 도착률):** 후보 r에서 워밍업 뒤 r건/초로 180초 유지한다. 계정은 1,800 + 180r개가 필요하다. 지연, 오류, 정합성, 미전송 0을 만족한 가장 높은 r이 C다. 실패하면 r을 낮춘다.
- **전후 비교:** 개선 전과 같은 r로 다시 유지해 지연을 비교하고, C가 올랐는지 새 r로 확인한다.
- 워밍업은 CSV 0 ~ 1,799행, 측정은 1,800행부터 쓴다. 워밍업 길이를 늘리면 오프셋과 계정 예산도 바꾼다.
- 잠금 보유는 `drop.inventory.lock.held`(UPDATE 완료부터 트랜잭션 종료까지)로 보고, 잠금 대기를 포함한 `drop.inventory.hold.update`와 구분한다. 앱에서 재므로 UPDATE 응답과 커밋 왕복이 더해진다.
- 잠금 모델을 대조할 때는 `capacity_single`(총재고 210,000개를 SKU 하나에)로 같은 측정을 한다. 배경 데이터와 총재고는 `capacity`와 같다.
- 수치만 맞추려고 인덱스, 풀, 코드를 한꺼번에 바꾸지 않는다.

## 4. 대표 판매 측정

`drop-sale.js`를 쓴다. 재고 1,000, 참여자 10,000명이다. 유입은 0에서 1,000명/초까지 5초 동안 올리고(2,500명), 1,000명/초로 7.5초 유지(7,500명)한 뒤 신규 유입은 0이다. 각 참여자는 한 번 진입하고 자동 재시도를 새 참여자로 세지 않는다.

- **대기열 끔:** 바로 점유한다.
- **대기열 켬:** `POST queue/entries` 본문에 SKU(`variantId`)를 담아 진입하고, 2초마다 `queue/me`로 조회하고, 입장 뒤 같은 SKU로 점유한다. 점유는 같은 SKU의 입장만 받는다. SKU는 계정 순서대로 돌아가며 배정하고 끔의 점유 배정과 같다. 2초는 실험 가정이지 API 계약상 의무가 아니다.
- 성공 점유는 가짜 PG 결제까지 잇는다. 끔과 켬은 같은 참여자별 도착 시간표, 재고, SKU, 결제 행동을 쓴다. 폴링 때문에 전체 HTTP RPS는 다르므로, API별 지연을 섞은 전체 p99만으로 개선을 주장하지 않는다.
- 입장은 한 주기에 SKU마다 `min(max(0, available - 미사용 유효 ADMITTED 수), admit-per-run)`명이다. 드롭 전체 입장률은 0.7 x C로 시작한다. C = 200건/초면 140건/초, 핫 SKU 3개와 1초 주기면 `admit-per-run` 약 47이다. fixedDelay라 실제 주기는 1초 + 실행 시간이므로 SKU별 admitted 증가율과 배치 시간을 보고한다.
- 신규 유입이 끝난 뒤 최소 120초, 최대 300초 관측한다. 끝나지 않은 참여자는 미완료와 경과 시간을 공개한다. 완료자만으로 전체 구매 지연을 대표하지 않는다.
- SKU의 `available = 0`이면 신규 진입은 409이고 남은 대기는 입장 허가 작업이 종료한다. 첫 `available = 0` 시각, 종료 완료 시각, 종료된 대기 수, 최종 순판매 1,000 시각을 구분해 남긴다. 반환 뒤 재진입은 관측하되 판정 대상이 아니다.
- 부하 발생기가 원안을 만들지 못하면 축소 시드 `sale_small`(500, 5,000, 500명/초), `sale_mini`(200, 2,000, 200명/초)로 진단한다. 축소 결과는 원안 합격을 대신하지 않는다.

## 5. 대기열 폴링 측정

입장 제어가 개선 수단일 때만 돌린다. `drop-queue-poll.js`를 쓴다.

- 입장 허가 작업을 끄고 대기자 N명을 초당 50명씩 채운다. 대기자는 핫 SKU 3개에 나뉜다. 각 대기자는 진입 즉시 2초마다 폴링한다.
- N명이 `WAITING`으로 안정되면 60초 워밍업 뒤 120초 측정한다. 인원이 모자라거나 이탈이 생기면 그 N은 실패다.
- N은 100, 500, 1,000, 2,000으로 시작하고 통과하면 늘린다. 진입과 조회 지연, 조회 RPS, CPU, SQL 실행 수, IO를 1,000명당으로 정규화하되 선형 비용이라고 가정하지 않는다.
- heartbeat 쓰기 간격 완화나 근사 순번은 개선 후보일 뿐이다. 이탈 의미와 응답 계약에 영향이 있어 이 문서가 정책 변경을 승인하지 않는다.

## 6. 예산

AWS 비용 한도는 팀 전체 75,000원(2026-12-07까지)이고 EC2와 RDS 실행 시간만큼 줄어든다. 인프라는 승인 없이 추가하지 않는다. 7절의 서버 두 대와 ALB는 쓰는 시간만큼 비용이 들므로 측정이 끝나면 바로 내린다. 실행 한 번은 준비 약 15 ~ 20분과 측정 약 5 ~ 15분이다. 하루 작업이 끝나면 EC2와 RDS 사용 계획을 확인한다. 예산이 모자라면 현재 회차의 전후 비교를 먼저 하고 대기열 폴링 측정을 미룬다.

PG 장애 복구는 범위 밖이다. 중복과 동시 구매, 환불, 이중 반환은 기존 통합 테스트와 동시성 테스트로 확인하고 별도 성능 목표로 세지 않는다.

## 7. ALB 멀티 인스턴스 측정

앱 서버 두 대를 ALB 뒤에 두고 같은 RDS를 쓰는 측정이다. 서버 수를 바꾸는 것이 변인일 때 쓴다. 이 절은 2절 순서를 서버 두 대로 넓힌 것이고 수치는 두지 않는다. 회차별 결과는 [#391](https://github.com/prgrms-be-devcourse/NBE12-14-final-RESTLESS/issues/391)에 있다. AWS 접속과 ALB 생성은 사용자가 한다. ALB는 중지가 안 되므로 측정이 끝나면 삭제하고 대상 그룹은 남긴다.

### 7-1. 준비 순서

먼저 필요한 것:

- 서버 2에도 `~/restless` 저장소(같은 커밋), `/opt/restless/perf.env`, docker가 있어야 한다. `perf-ec2.sh start`가 서버 2에서도 자동 배포를 멈추고 `restless` 컨테이너를 지운다.
- 보안 그룹은 노트북에서 서버 2의 8081을 열고, ALB에서 두 서버의 8080과 8081(헬스 체크)을 연다.
- 서버 두 대는 같은 이미지 태그를 쓴다. 다르면 달라진 변경을 기록에 적는다.
- 두 서버의 `perf.env`는 같은 `perf-` DB와 같은 풀, Tomcat, 대기열, 가짜 PG 값을 가진다. 아래 입장 허가 설정만 예외다.

주의할 점:

- 입장 허가 작업, 순번 스냅샷, 점유 만료 작업은 앱 인스턴스마다 돈다. 서버 둘이 같은 `admit-per-run`을 쓰면 초당 입장 수가 약 2배가 되어 서버 수 말고도 변인이 바뀐다. 기본은 서버 2의 `RESTLESS_DROP_QUEUE_SCHEDULER_ENABLED`를 false로 두는 것이다. 입장 수가 한 대일 때와 같다. 대안으로 서버마다 `RESTLESS_DROP_QUEUE_ADMIT_PER_RUN`을 절반으로 둘 수 있지만, 두 서버의 주기가 겹치면 한쪽이 그 SKU를 건너뛰어 합이 한 대일 때보다 낮아질 수 있다. 고른 방법을 실행 기록에 적는다.
- `perf-ec2.sh check`는 대기열이 false가 아니면 경고를 낸다. 대기열 켬 측정이면 정상이다.

순서:

1. 노트북 `/etc/hosts`에 서버 1, 서버 2, ALB를 모두 `perf-`로 시작하는 이름으로 둔다 (예: `perf-app`, `perf-app2`, `perf-alb`). ALB 주소는 바뀔 수 있으니 측정하는 날 `dig +short <ALB DNS>`로 다시 보고 사용자가 고친다. 서버 2 주소도 재시작하면 바뀔 수 있다. ALB 리스너 포트도 확인한다. 아래 k6 예시는 80이다.
2. 서버 2에서 `perf-ec2.sh stop-app`을 먼저 한다. 컨테이너가 없으면 오류가 나도 된다. 앱이 떠 있으면 DB 초기화 중에 잠금을 기다리거나 새 시드에 쓰기를 한다. 서버 2는 5번까지 멈춰 둔다.
3. 서버 1에서 2절 2번처럼 `stop-app`, `reset-db`, `start` (Flyway 실행), `stop-app`, `seed sale`을 한다. Flyway와 시드는 서버 1에서만 한다. 서버 2는 DB를 만들거나 시드하지 않는다.
4. 서버 1에서 `serve-tokens`로 토큰 CSV를 노트북에 받는다. 서버 2에는 시드와 토큰이 필요 없다.
5. 서버 1과 서버 2에서 같은 태그로 `start`를 한다. 서버 2의 `start`는 Flyway를 새로 적용하지 않고 이미 적용된 결과만 보여야 한다.
6. 대상 그룹에서 두 서버가 healthy인지 본다. 콘솔을 쓸 수 없으면 측정 뒤 서버별 요청 수가 고른지로 분배를 확인한다.
7. 서버 1에서 `lockstats`와 DB 프로브 `status`를 측정 전 값으로 남긴다 (7-3절).

### 7-2. 서버별 지표 수집과 k6

노트북에서 창 두 개를 열고 서버마다 관리 포트 수집을 시작한다. 5초 간격이다. 한 서버의 값만으로 두 서버를 대표하지 않는다.

```bash
perf/scenarios/prom-collect.sh http://perf-app:8081  .ai-output/perf/<날짜>-<측정>/prom-server1
perf/scenarios/prom-collect.sh http://perf-app2:8081 .ai-output/perf/<날짜>-<측정>/prom-server2
```

DB 프로브 `sample`은 k6를 시작하기 직전에 서버 1의 두 번째 터미널에서 시작한다. 초 수는 워밍업, 판매 스파이크, 관측 구간을 모두 덮게 정한다. 브라우저 터미널에서는 끝날 때까지 막히므로 별도 창에서 돌린다.

k6는 ALB로 보내고 관리 포트 지표는 서버 1에서 읽는다. 칠 수 있는 호스트를 모두 허용 목록에 넣는다.

```bash
ulimit -n 65536
BASE_URL=http://perf-alb \
MANAGEMENT_URL=http://perf-app:8081 \
PERF_ALLOWED_APP_HOSTS=perf-alb,perf-app \
SEED_OUT="$PWD/perf/seed/out" QUEUE=on \
SUMMARY_PATH=.ai-output/perf/<날짜>-<측정>/summary.json \
k6 run perf/scenarios/drop-sale.js 2>&1 | tee .ai-output/perf/<날짜>-<측정>/k6.log
```

- 시나리오는 호스트가 `perf-`로 시작하지 않으면 시작하지 않는다. ALB 이름도 `perf-`로 시작하게 `/etc/hosts`에 둔다.
- `ulimit -n`을 올리지 않으면 부하 발생기 쪽 연결 오류가 서버 성능으로 오해된다.
- 풀 크기와 대기열 설정은 서버마다 `curl http://perf-app:8081/actuator/prometheus`와 `perf-app2`의 값으로 따로 확인한다. k6의 시작 점검은 `MANAGEMENT_URL`의 서버 1만 본다.
- 서버 두 대의 앱 CPU, Tomcat busy, Hikari active와 pending을 서버별로 따로 본다. 요청 수가 고르게 나뉘었는지도 서버별 `http.server.requests` 합으로 확인한다.
- 수집이 가끔 시간 초과로 빠질 수 있다. 빠진 횟수를 기록한다. 스파이크 한가운데에서 빠졌는지 본다.

### 7-3. DB 프로브

RDS 콘솔의 CPU는 1분 값이고 Performance Insights와 performance_schema를 못 쓸 때가 있다. 그때는 [db-probe.sh](../../perf/ec2/db-probe.sh)로 대신한다. 읽기 전용이고 사용법은 [EC2 도구](../../perf/ec2/README.md#db-프로브)에 있다.

- `status`: 측정 전과 후에 한 번씩 전역 카운터를 저장하고 차이를 본다. 커밋 수, redo fsync, 읽은 행 수, 잠금 대기를 얻는다. 이 값은 RDS 인스턴스 전체이므로 다른 테스트가 없는지 확인한다.
- `sample`: 측정 중 약 0.5초마다 실행 중인 SQL을 표본으로 모은다. 간격에 질의 시간이 더해져 실제 수집은 `<초>`보다 조금 길다. 질의가 없는 순간에도 poll마다 `tick` 행이 한 줄 남으므로 `tick` 행 수로 poll 횟수와 수집이 끝났는지 안다. 스파이크 전에 시작해 스파이크 뒤까지 돌린다. 표본 몫은 CPU 시간이 아니라 실행 중인 SQL의 몫이다.
- 표본 수집이 요약 시점에 끝나지 않았으면 그 사실을 적는다.
- 표본을 SQL 종류별로 묶을 때는 문장 앞부분으로 나눈다. 순번 COUNT, COMMIT, 행 잠금 SELECT, heartbeat UPDATE 같은 몫을 본다.

### 7-4. JFR 켜기와 꺼내기

앱 CPU가 첫 병목일 때 무엇이 CPU를 쓰는지 보려고 켠다. 프로파일러 부담이 있으므로 JFR을 켠 회차의 처리량을 켜지 않은 회차와 바로 비교하지 않는다. 서버 한 대에만 켜서 다른 서버와 비교하는 방법도 있다.

1. 켤 서버에서 `perf-ec2.sh stop-app`으로 멈춘 뒤 컨테이너를 지우고 직접 띄운다. `perf-ec2.sh start`는 JVM 옵션을 받지 않는다. 이미지 기본 옵션을 덮어쓰므로 기본값도 같이 적는다.

```bash
docker rm -f restless-perf
docker run -d --name restless-perf --env-file /opt/restless/perf.env -p 8080:8080 -p 8081:8081 \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -XX:FlightRecorderOptions:stackdepth=256 -XX:StartFlightRecording=settings=profile,filename=/tmp/run.jfr,dumponexit=true" \
  ghcr.io/prgrms-be-devcourse/nbe12-14-final-restless-backend:<태그>
curl -sf http://127.0.0.1:8081/actuator/health   # UP이 될 때까지 반복한다
```

2. 스택 깊이 기본값 64는 Tomcat과 앱 코드 같은 바깥 계층을 과소 집계한다. 위 명령은 256으로 올렸다.
3. 측정 뒤 `docker stop -t 60 restless-perf`로 종료한다. 종료할 때 파일이 저장되므로 기본 10초로 끊지 않는다. 컨테이너를 지우기 전에 `docker cp restless-perf:/tmp/run.jfr ~/run-<날짜>.jfr`로 서버 홈에 꺼낸다. 저장소 폴더에는 두지 않는다. 노트북에는 서버 접속 방식대로 받아 결과 폴더에 둔다.
4. 판매 1분 창의 실행 샘플만 모아 처음 만나는 JDK 밖 프레임 기준으로 묶는다. `jfr print --events jdk.ExecutionSample`과 `jdk.CPULoad`, `jdk.GarbageCollection`을 쓴다. self 몫만 믿는다. 깊이 제한이 있으면 엔드포인트별 CPU는 나눌 수 없다.
5. 끝나면 서버를 `perf-ec2.sh start <태그>`로 되돌린다. JFR 파일은 커밋하지 않는다.

### 7-5. 측정 뒤 확인

1. 수집을 멈추고 서버마다 8081에서 `after-run.prom`을 받는다. 서버 1에서 `lockstats`와 DB 프로브 `status`를 후 값으로 남긴다.
2. 진행 중 거래가 수렴했는지 보고 서버 1에서 `verify sale`을 돌린다. 출력을 결과 폴더에 저장한다. tail만 보지 않는다.
3. 실패 항목을 이름으로 확인한다. 부하 뒤 정상인 실패는 hot drop untouched 한 건뿐이다. 그 밖의 실패는 정합성 위반으로 보고 그 회차를 채택하지 않는다.
4. 정리는 이 순서로 한다. 사용자가 한다. 먼저 팀원이 싱글 테스트에 쓰는 서버를 대상 그룹에서 등록 해제한다. 멀티 측정이 모두 끝나면 ALB를 삭제하고 대상 그룹은 남긴다. perf 전용 기간에는 `finish`를 하지 않는다 ([EC2 도구](../../perf/ec2/README.md)).
5. 하루 작업이 끝나면 6절대로 EC2, RDS, ALB 사용 계획을 확인한다.

회차 사이의 흔들림이 크다. 반복 횟수와 판정은 [측정 규약 5절](drop-performance-measurement.md#5-반복-횟수)을 따른다. 처리량 하나로 판정하지 않고 크게 움직인 서버 지표를 함께 본다.

서버 두 대 구성은 측정을 위한 것이다. 다중 EC2 도입 여부는 [열린 결정 사항](../product/open-questions.md#3-비교-실험-뒤-결정)이 정하며 이 절이 정하지 않는다.
