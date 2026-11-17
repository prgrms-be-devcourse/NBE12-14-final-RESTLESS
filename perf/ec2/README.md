# EC2에서 부하 테스트 준비

배포 서버를 테스트 시간에만 빌려 perf 프로필로 띄운다. [perf-ec2.sh](perf-ec2.sh)가 EC2 쪽 단계를 묶는다.
EC2 Instance Connect 브라우저 터미널에서 `~/restless` 저장소로 실행한다. k6는 노트북에서 돈다. 서버 자원은 CloudWatch로 본다.

- 비밀값은 EC2의 `/opt/restless/perf.env`에만 있다. 스크립트는 이 파일을 서버 안에서만 읽고 값을 출력하지 않는다.
- 시드는 EC2에서 돌린다. perf `JWT_SECRET`이 서버 밖으로 나가지 않는다. 노트북에는 서명된 토큰 CSV만 옮긴다.
- 대상 DB는 `perf.env`의 `DB_URL`에서 읽는다. 이름이 `perf-`로 시작하지 않거나 prod가 들어 있으면 멈춘다.
- EC2에 `mysql` 클라이언트가 없으면 `mysql:8.4` 컨테이너의 클라이언트를 쓴다. 매번 컨테이너를 띄우므로 `sudo apt-get install -y mysql-client-core-8.0`을 권한다.

## 순서

0. 같은 서버와 DB를 쓰는 다른 도메인 담당자와 시간을 나눈다. 각자 `perf-` 접두어의 다른 DB를 쓰는지 확인한다.
1. 콘솔에서 EC2 CPU 크레딧을 무제한으로 바꾸고, CloudWatch `CPUCreditBalance` 시작값을 적는다.
   EC2 세부 모니터링(1분 간격)과 RDS 향상된 모니터링을 켠다. 끝나면 끈다.
2. 노트북: `/etc/hosts`에 `3.35.88.242 perf-app`을 둔다.
3. EC2: 저장소를 테스트할 브랜치로 맞추고 준비한다.

```bash
cd ~/restless && git fetch -q && git checkout <브랜치> && git pull -q
perf/ec2/perf-ec2.sh check                 # 프로필, DB 이름, 대기열 꺼짐, 가짜 PG 값
perf/ec2/perf-ec2.sh create-db             # 처음 한 번
perf/ec2/perf-ec2.sh start sha-<12자리>     # 자동 배포 멈춤, restless 중지, restless-perf 기동, Flyway 결과
perf/ec2/perf-ec2.sh stop-app
perf/ec2/perf-ec2.sh seed capacity         # 계단식은 capacity, 스파이크는 base
perf/ec2/perf-ec2.sh serve-tokens          # 노트북이 받으면 바로 끝난다
```

4. 노트북: 토큰 묶음을 받는다. 8080은 보안 그룹에서 노트북 IP에만 열려 있다.

```bash
mkdir -p perf/seed/out && curl -fo perf/seed/out/seed-out.tar.gz http://perf-app:8080/seed-out.tar.gz
tar -xzf perf/seed/out/seed-out.tar.gz -C perf/seed/out && rm perf/seed/out/seed-out.tar.gz
```

5. EC2: 앱을 다시 띄우고 측정 직전 잠금 누적값을 적는다.

```bash
perf/ec2/perf-ec2.sh start sha-<같은 태그>
perf/ec2/perf-ec2.sh lockstats
```

6. 노트북에서 시나리오를 실행한다 ([scenarios](../scenarios/README.md)).
7. 실행마다 EC2에서 `lockstats`와 `verify <scale>`을 실행한다. 드롭 불변식, 금액, FK 고아 행 검사는 0이어야 한다. `seed baseline` 검사는 부하 뒤에 실패하는 것이 정상이다.
8. 계정을 다 썼거나 스케일을 바꿀 때는 DB를 다시 만든다.

```bash
perf/ec2/perf-ec2.sh stop-app && perf/ec2/perf-ec2.sh reset-db
perf/ec2/perf-ec2.sh start sha-<태그> && perf/ec2/perf-ec2.sh stop-app   # Flyway
perf/ec2/perf-ec2.sh seed <scale> && perf/ec2/perf-ec2.sh serve-tokens
```

9. 끝나면 정리한다. 배포 컨테이너는 3분 안에 다시 뜬다.

```bash
perf/ec2/perf-ec2.sh finish
```

콘솔에서 CPU 크레딧을 표준으로 되돌리고 `CPUCreditBalance` 끝값을 적는다. 오래 쓰지 않으면 EC2, RDS 순서로 중지한다.

## 기록할 값

| 항목 | 위치 |
|---|---|
| 이미지 태그와 커밋 | `start` 출력 |
| 시드 스케일, 걸린 시간 | `seed` 출력 |
| Lock Wait | 측정 전후 `lockstats` 차이 (`Innodb_row_lock_waits`, `_time`) |
| 처리량, 오류율, 지연 | k6 요약과 웹 대시보드 리포트. 지연에는 왕복 지연이 섞이므로 테스트 전 왕복 지연을 같이 적는다 |
| EC2 CPU | CloudWatch `CPUUtilization` (fanq-server, 세부 모니터링 1분) |
| EC2 CPU 크레딧 | CloudWatch `CPUCreditBalance` (fanq-server) |
| EC2 메모리 | CloudWatch 기본 지표에 없다. 테스트 중 EC2에서 `docker stats restless-perf`로 본다 |
| RDS CPU, 메모리 | CloudWatch `CPUUtilization`, `FreeableMemory` (fanq-db). 향상된 모니터링의 OS 지표 |
| 정합성 | `verify` 출력 |
