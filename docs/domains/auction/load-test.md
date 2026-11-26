# 경매 부하 테스트 계획

> 관련 코드: `perf/scenarios/auction-burst.js`, `perf/scenarios/auction-sse.mjs`, `perf/scenarios/auction-run.sh`
>
> 마지막 확인: 2026-11-26 (8,000 → 10,000 몰림 시나리오. 2026-11-18, 2026-11-20에 AU-1~4를 측정. SSE 연결 상한(#244)과 heartbeat(#447) 뒤 2026-11-26에 AU-4~8을 측정. 시드 토큰 인증과 응답 시간 나누기는 #483)

이 계획은 인기 경매 하나에 부하가 집중되는 상황을 측정합니다. 나머지 9만 명의 배경 트래픽은 만들지 않습니다.

## 1. 목표와 가정

- 서비스 동시접속 10만 명 중 1만 명이 인기 경매 하나에 몰린다고 가정합니다.
- 약 1초 안에 1만 건이 들어오는 상황을 재현합니다. k6 한 번 실행에서 8,000건 판 다음 10,000건 판을 이어서 보냅니다.
- 결과를 보기 전에 합격선을 정하지 않습니다. 사람이 판단할 수 있도록 측정값을 기록합니다.
- 정확성 조건은 0이어야 합니다. 5xx, 응답받지 못한 수락 입찰, 잘못된 낙찰, 최종 종료 시각 뒤 수락(INV-AU-11)은 허용하지 않습니다.
- 입찰자와 관전자는 기본으로 시드가 만든 토큰(`perf/seed/out/users.csv`)을 씁니다 (`AUTH_MODE=seed`). 로그인은 측정 대상이 아니고, 로그인 시간(1만 명 약 11분)과 로그인 CPU가 측정에 섞이기 때문입니다 (2026-11-26 AU-7에서 로그인 초당 30회가 2 vCPU를 포화시켰다). 로그인 경로까지 포함하려면 `AUTH_MODE=login`으로 실행합니다. 주최자는 경매 등록을 위해 한 번 로그인합니다. 토큰 받는 법은 [5절](#시드-토큰-받기)에 있습니다.
- AU-5는 지속 가능한 SSE 연결 수를 보기 위해 별도 연결 계단을 사용합니다.

## 2. 시나리오

| ID | 상황 | API | 인증 |
|---|---|---|---|
| AU-1 | 마감 직전 입찰 몰림 | `POST /api/auctions/{id}/bids` | 필요 |
| AU-2 | 인기 경매 상세 조회 몰림 | `GET /api/auctions/{id}` | 현재 구현에서 공개 |
| AU-3 | 경매 목록 조회 몰림 | `GET /api/auctions?status=OPEN&page=0&size=20` | 현재 구현에서 공개 |
| AU-4 | 평시 입찰 몰림 | `POST /api/auctions/{id}/bids` | 필요 |
| AU-5 | 실시간 관전 연결 유지, 연결 상한 | `GET /api/auctions/{id}/stream` | 필요 |
| AU-6 | 떠난 연결 정리 (heartbeat) | `GET /api/auctions/{id}/stream` | 필요 |
| AU-7 | 관전과 입찰 몰림 동시 | stream + `POST /api/auctions/{id}/bids` | 필요 |
| AU-8 | 관전 연결 장시간 유지 | `GET /api/auctions/{id}/stream` | 필요 |

AU-1은 기본적으로 판 시각 T에서 1,000ms 뒤를 원래 종료 시각으로 둡니다. 수락된 입찰은 `max(현재 종료 시각, acceptedAt + 60초)`까지 종료 시각을 연장합니다. 원래 마감 전에 보냈는데 `AUCTION_CLOSED`로 거절된 수, 최종 종료 시각, 종료 처리 지연을 기록합니다. 최종 결과는 낙찰자가 한 명이어야 하며 마지막 수락 입찰자가 낙찰되어야 합니다.

AU-2·AU-3은 현재 `SecurityConfig`가 공개한 GET이므로 토큰 없이 호출합니다. 401이면 공개 GET 경로가 막힌 것으로 표시합니다.

AU-4는 각 판마다 새 경매를 사용합니다. 각 입찰자는 시드 토큰(또는 `AUTH_MODE=login`이면 한 번 로그인)으로 참여하는 각 판에서 한 번 입찰합니다. `PHASES`는 판 하나(`8000`) 또는 둘(`8000,10000`)을 받습니다. AU-5는 SSE 연결을 단계적으로 늘리고 각 단계에서 측정 입찰을 한 번 보냅니다. 현재 최고 입찰자의 연속 입찰이 거절되므로 측정 계정 두 개를 번갈아 씁니다. 스크립트는 예정된 로그인·연결 시간과 60초 입찰 연장을 고려해 경매 수명을 잡고 마지막 CLOSED 이벤트를 기다립니다. 제한 시간 안에 종료 이벤트가 오지 않으면 클라이언트 스트림을 닫고, 정리 입찰을 별도로 기록해 끊긴 emitter 정리를 유도합니다. 측정 revision을 받은 연결 수와 종료 전후 서버 연결 게이지를 기록합니다. 상한을 넘는 단계의 503 `AUCTION_STREAM_BUSY`는 실패가 아니라 "상한 거절"로 따로 세고 `Retry-After` 값을 기록합니다.

AU-6~8은 `auction-sse.mjs`의 `MODE`로 실행합니다.

- AU-6 (`MODE=cleanup`): 연결을 연 뒤 입찰 없이 클라이언트만 모두 끊고, 서버 연결 게이지가 측정 전 값으로 돌아오기까지 걸린 시간을 1초 간격으로 기록합니다. heartbeat 간격(15초)의 약 2배 안이어야 합니다.
- AU-7 (`MODE=mixed`, `AUCTION_ID`): `PHASES=8000 auction-run.sh bid`가 만든 경매에 관전 연결을 붙이고, 입찰은 그 실행이 보냅니다. 관전자마다 받은 이벤트 수, 최종 revision 도달 여부, 마지막 수락 입찰부터 최종 가격을 받기까지의 시간(서버 시계 기준)을 기록합니다.
- AU-8 (`MODE=soak`): 입찰 없이 `HOLD_SECONDS` 동안 연결을 유지하며 `SNAPSHOT_SECONDS`마다 열린 연결, 끊긴 연결, 서버 게이지, heartbeat 수신 수를 기록합니다. 끝에 AU-6과 같은 정리 시간도 기록합니다.

## 3. 공통 기록과 시나리오별 기록

각 판에서 다음을 기록합니다.

- 보낸 요청, 받은 응답, 연결 실패, 5xx, 상태 및 업무 오류 코드별 수
- 응답 지연 p50/p95/p99/최대, T부터 1초 안에 응답한 비율, T부터 마지막 응답까지의 시간, 요청 전송 시각 퍼짐
- 조건: 대상, 판 크기, 경매 ID, 입찰자 ID 범위, 인증 방식(`authMode`), 로그인 요청·성공·시간(로그인 방식일 때), 실행 옵션
- 응답 시간 나누기 (`timingMs`, 응답을 받은 요청만): 연결 차례 대기(`blocked`), TCP 연결 맺기(`connecting`), 요청을 보낸 뒤 첫 바이트까지(`waiting`)의 p50/p95/최대. `connecting`이 길면 노트북·네트워크 쪽 연결 지연이고, `waiting`이 길면 서버 쪽 대기·처리입니다. 서버 처리 시간(`http_server_requests_seconds`, 실행 전후 지표 차이)과 함께 봅니다
- 입찰: 201 응답 수와 revision으로 계산한 서버 수락 수 및 차이
- AU-1: 원래·최종 `endsAt`, 연장 시간, `AUCTION_CLOSED` 총수, **원래 마감 전에 보냈는데 `AUCTION_CLOSED`로 거절된 수**, 수락 수, SOLD/UNSOLD, 낙찰가, 종료 처리 지연, 마지막 수락 입찰과 낙찰가 일치 여부
- AU-5: 연결 시도, 성공(HTTP 200 및 초기 이벤트), 상한 거절 수와 `Retry-After`, 상태별 실패, 끊김, 열린 연결, 변경 revision 수신 연결 수, 이벤트 지연 p50/p95/최대, `auction_stream_connections`, 서버 거절 수 `auction_stream_rejected_total`
- AU-6~8: heartbeat 수신 수와 최대 간격, 정리 시간과 게이지 변화, 서버 정리 수 `auction_stream_heartbeat_removed_total`
- AU-4: 사전 확인 결과별 수 `auction_bid_precheck_total{result,reason}` (실행 전후 전체 지표 `-prom-before.txt`, `-prom-after.txt`의 차이)

5xx, 연결 실패, 수락 입찰 수 불일치, 낙찰자 불일치, **최종 종료 시각 뒤 수락(INV-AU-11)**은 하나라도 있으면 결과에 경고합니다. 원래 마감 뒤 수락은 입찰에 따른 연장 정책상 정상일 수 있으므로 오류로 세지 않습니다. 합격 지연이나 처리량 기준은 두지 않습니다.

입찰 API 응답에는 revision이 없습니다. OPEN 경매에서는 최초 OPEN revision에 수락 입찰 수를 더하고, CLOSED 경매에서는 최초 OPEN revision에 수락 입찰 수와 종료 전환을 더합니다. `Auction.openIfDue()`, `Auction.acceptBid()`, `Auction.close()`가 각각 한 번씩 revision을 올리므로 OPEN일 때 수락 수는 `revision - 1`, 종료 뒤에는 `revision - 2`입니다. 마지막 `acceptedAt`과 가장 큰 수락 응답 금액을 기록합니다. 입찰가가 최소 인상액 이상씩 올라가므로 가장 큰 금액은 마지막 수락 입찰에 해당하고 SOLD 결과 금액과 대조합니다.

## 4. 서버에서 확인할 값

클라이언트는 k6 JSON, k6 HTML, 로그, 2초 간격 Actuator 샘플, 실행 전후 전체 지표를 저장합니다. 노트북 샘플은 몰림 순간에 수집 요청도 밀려 행이 빌 수 있으므로, 몰림 구간은 서버 안 1초 샘플(`perf-ec2.sh sample <초>`, [RUNBOOK](../../../perf/ec2/RUNBOOK.md))로 봅니다. 샘플에는 힙, 힙 밖 메모리, direct buffer, SSE 거절·정리 수, 사전 확인 통과·거절 수가 들어 있어 연결당 메모리를 힙 밖까지 계산할 수 있습니다. 필요한 경우 서버에서 별도로 확인합니다.

- 연결당 메모리 구성: perf 프로필은 관리 포트에 `/actuator/heapdump`를 엽니다. 관리자 토큰으로만 받을 수 있고, 덤프를 만드는 동안 앱이 멈추므로 측정이 끝난 뒤 받습니다. 덤프에는 비밀값이 들어 있으므로 공유하지 않고 분석 뒤 지웁니다.

- 경매 불변식: 측정 전과 하루 측정 뒤 `perf/ec2/perf-ec2.sh verify capacity`로 확인 (측정은 `capacity` 시드로 합니다)
- EC2 CPU credit 모드: 필요할 때 실행 전에 확인 또는 unlimited 설정
- DB 행 잠금 통계, RDS CPU, `netstat`, 서버 로그: 결과에서 원인이 필요할 때 확인

시나리오는 서버 설정을 바꾸거나 다른 도메인 부하를 만들지 않습니다.

## 5. 진행 순서

시드는 측정 기간 시작에 한 번 준비합니다. 스크립트가 각 판에 새 경매를 등록하므로 판 사이에 perf DB를 복원할 필요는 없습니다. 하루의 측정이 끝나면 불변식을 확인하고 결과를 정리합니다.

### 시드 토큰 받기

시드(`seed`, `refresh`)가 서버에 토큰 묶음을 만듭니다. 팀원 계정은 앱을 켠 채로 그 묶음을 읽을 수 있습니다 ([RUNBOOK](../../../perf/ec2/RUNBOOK.md#팀원-계정으로-실행)). 노트북에서:

```bash
mkdir -p perf/seed/out
ssh <팀원 계정>@<perf 서버> 'sudo -u ubuntu cat /home/ubuntu/restless/.ai-output/perf-tokens/seed-out.tar.gz' > perf/seed/out/seed-out.tar.gz
tar -xzf perf/seed/out/seed-out.tar.gz -C perf/seed/out && rm perf/seed/out/seed-out.tar.gz
```

- 토큰은 시드 시각부터 72시간 유효합니다 (`perf/seed/scale.yml`의 `token_ttl_hours`). 스크립트는 실행이 끝나기 전에 만료되면 경매를 만들기 전에 멈춥니다. 그때는 서버에서 `perf-ec2.sh refresh <scale>`을 실행하고 다시 받습니다. `refresh`는 드롭 판매 기간도 다시 맞추므로 다른 도메인 측정과 겹치지 않을 때 합니다.
- `users.csv`에 필요한 회원(입찰자 `fanq1`~판 크기, 관전자 `ACCOUNTS`+측정 계정 2개)이 없으면 시작하지 않습니다.
- 토큰 파일은 git에 올리지 않습니다 (`perf/seed/out/`은 무시 목록에 있다).

1. AU-2 상세 조회
2. AU-3 목록 조회
3. AU-4 평시 입찰 몰림
4. AU-1 마감 직전 입찰 몰림
5. AU-5 SSE 연결 유지와 상한
6. AU-6 떠난 연결 정리
7. AU-7 관전과 입찰 동시
8. AU-8 관전 연결 장시간 유지

각 k6 실행은 8,000건 판 다음 10,000건 판을 이어서 수행합니다. 실행기로 `.ai-output/perf/auction/`에 `.json`, `.html`(k6), `.log`, `-metrics.csv`를 저장합니다. 실험 보고서도 같은 폴더에 실험별로 작성하고 레포에 올리지 않습니다.

## 6. 알려진 한계

- 부하 생성기는 Mac 한 대이므로 CPU, 파일 디스크립터, 네트워크 스택과 가정용 회선이 병목일 수 있습니다.
- Tomcat 최대 연결 수는 perf 서버 환경 설정으로 15,000에 두고 측정합니다 (2026-11-18 오후부터, 레포 설정 파일에는 없음). 실행 전 Actuator의 `tomcat.connections.config.max`로 확인합니다.
- SSE 연결 상한은 6,000입니다 (#244). 상한이 없던 2026-11-18에는 약 9,400개에서 JVM 힙이 가득 차 앱이 종료됐습니다.
- AU-7은 관전 연결과 입찰 연결이 Mac 한 대에서 함께 나갑니다. 관전 6,000에 입찰 8,000이면 14,000으로, Tomcat 최대 연결 15,000과 Mac 임시 포트(약 16,000) 아래에 둡니다. 10,000 판은 섞지 않습니다. 그래도 2026-11-26에는 입찰 620건, 관전 82건이 연결을 맺지 못했습니다 (서버 커널 대기열 넘침 0). `timingMs.connecting`으로 노트북 쪽 지연을 따로 봅니다.
- macOS는 관리자 권한 없이는 TCP 통계(`netstat -s`)를 0으로 보여 줍니다 (`net.inet.tcp.disable_access_to_stats=1`). 그래서 노트북 쪽 연결 지연은 k6의 요청별 시간(`timingMs`)으로 봅니다.
- 브라우저가 종료 신호 없이 사라지는 경우(절전, 네트워크 단절)는 Mac 한 대로 재현하기 어려워 AU-6에서 다루지 않습니다.
- 가정한 나머지 9만 명의 배경 부하는 만들지 않습니다.
- `AUTH_MODE=login`이면 로그인을 기본 초당 15회로 제한합니다. 입찰자 10,000명은 첫 판 전 여유 시간을 제외하고 약 11분이 걸립니다. 2 vCPU 서버에서 초당 30회는 CPU가 포화되어 로그인이 시간 초과됩니다 (2026-11-26).
- AU-2·AU-3 GET은 현재 구현에서만 공개입니다. 인증 정책이 바뀌면 시나리오를 조정해야 합니다.
- SSE는 Node 24 내장 fetch를 사용합니다. `PERF_APP_IP`로 서버 주소를 전달하고 `Host: perf-local`을 보냅니다. 허용 목록에 든 perf 호스트만 허용합니다.
- 실행할 때마다 측정용 경매가 DB에 추가됩니다. 독립적인 측정 기간 사이에는 새 시드를 준비합니다.
