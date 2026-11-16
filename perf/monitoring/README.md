# 부하 테스트 모니터링 (노트북)

부하 테스트 중 perf 앱의 서버 지표를 노트북에서 본다. 지연은 이 화면의 서버 지표를 기준으로 쓴다.
k6 지연에는 집 네트워크 왕복이 섞이므로 사용자 체감값으로만 기록한다 ([perf/README.md](../README.md#이-환경에서-믿을-수-있는-것)).

| 구성 | 주소 | 내용 |
|---|---|---|
| Prometheus | http://127.0.0.1:9090 | `perf-app:8081/actuator/prometheus`를 5초마다 수집, 30일 보관 |
| Grafana | http://127.0.0.1:3000 | 대시보드 `RESTLESS perf (서버 지표)`. 로그인 없음 |
| k6 웹 대시보드 | http://127.0.0.1:5665 | k6가 도는 동안만 열린다. 끝나면 HTML 리포트로 남긴다 |

## 준비

노트북의 `/etc/hosts`에 perf 앱 이름을 둔다. 브라우저와 curl용이다. 컨테이너는 이 파일을 읽지 않으므로 아래 명령마다 주소를 따로 넘긴다.

```bash
echo '3.35.88.242 perf-app' | sudo tee -a /etc/hosts
curl -s http://perf-app:8081/actuator/health   # {"status":"UP"} 이면 perf 앱과 보안 그룹이 맞다
```

보안 그룹은 8080, 8081을 노트북 IP에만 연다. 집 IP가 바뀌면 수집이 끊기므로 Prometheus의 Targets 화면에서 `up`인지 먼저 본다.

## 실행

레포 루트에서 실행한다.

```bash
PERF_APP_IP=3.35.88.242 docker compose -f perf/monitoring/compose.yaml up -d
docker compose -f perf/monitoring/compose.yaml down        # 지표는 볼륨에 남는다. 지우려면 down -v
```

`PERF_APP_IP`를 비우면 노트북에서 띄운 perf 앱(`host-gateway`)을 수집한다. 시나리오를 로컬에서 미리 확인할 때 쓴다.

## 패널

| 패널 | 지표 | 보는 것 |
|---|---|---|
| 서버 지연 p50/p95/p99 | `http_server_requests_seconds_bucket` | uri별 서버 처리 시간. 위쪽 `uri` 변수로 거른다 |
| 요청 수 | `http_server_requests_seconds_count` | uri별 RPS |
| 상태 코드별 비율 | 같은 지표의 `status` | 409 품절, 활성 거래 존재는 정상 거절. 5xx만 오류 |
| 재고 점유 결과 | `drop_reservation_total{result}` | created, sold_out, active_exists, replayed, not_on_sale |
| 재고 점유 처리 시간 | `drop_reservation_duration_seconds` | 서비스 안에서 잰 시간. 서버 지연과 차이가 크면 서비스 밖 대기다 |
| Hikari 커넥션 | `hikaricp_connections_{active,idle,pending,max}` | pending이 계속 0보다 크면 커넥션 풀 병목 |
| Hikari 획득 시간 | `hikaricp_connections_acquire_seconds`, `_timeout_total` | 커넥션을 얻기까지 기다린 시간과 타임아웃 |
| 톰캣 스레드 | `tomcat_threads_*{name="http-nio-8080"}`, `http_server_requests_active_seconds_gcount` | busy가 max에 닿으면 요청이 톰캣 큐에서 기다린다 |
| CPU | `process_cpu_usage`, `system_cpu_usage` | 0~1. t3.small은 vCPU 2개 |
| JVM 힙, GC | `jvm_memory_*{area="heap"}`, `jvm_gc_pause_seconds` | 힙 포화와 GC 정지 |

RDS CPU, Lock Wait, CPU 크레딧은 앱 지표에 없다. CloudWatch(`CPUUtilization`, `CPUCreditBalance`)와 DB 쿼리로 따로 기록한다.
Lock Wait은 측정 직전과 직후에 `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'`를 떠서 차이를 본다.

## k6 웹 대시보드와 리포트

시나리오 명령에 아래를 더한다. 리포트 HTML은 `.ai-output/perf/`에 남는다 (git에 올리지 않음).

```bash
docker run --rm -p 5665:5665 \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 \
  -e K6_WEB_DASHBOARD_EXPORT=/out/<실행 이름>.html \
  ... grafana/k6:2.4.0 run /perf/scenarios/<시나리오>.js
```

## 실행마다 같이 기록할 것

부하 발생기가 먼저 막혔는지 판단하는 근거다. 기준은 [부하 발생기를 EC2로 옮기는 기준](../README.md#부하-발생기를-ec2로-옮기는-기준)에 있다.

| 항목 | 얻는 법 |
|---|---|
| 서버까지 왕복 지연 | 테스트 전 `for i in $(seq 20); do curl -so /dev/null -w '%{time_connect}\n' http://perf-app:8081/actuator/health; done` 의 중앙값 (TCP 연결 시간 = 왕복 1회) |
| 노트북 CPU | 테스트 중 `top -l 0 -s 5 -stats pid,command,cpu \| grep -E 'CPU usage'` 또는 `docker stats` |
| `dropped_iterations`, `http_req_connecting`, `http_req_blocked` | k6 요약과 웹 대시보드 리포트 |
| 네트워크 | 와이파이 대역(2.4/5GHz) 또는 유선 |
