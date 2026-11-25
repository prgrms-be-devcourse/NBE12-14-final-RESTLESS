# EC2 부하 테스트 런북

EC2 담당자는 `~/restless`에서 `perf/ec2/perf-ec2.sh <명령>`으로 실행한다. 팀원은 각자 계정으로 SSH 접속해 `$R <명령>`으로 실행한다. 팀원용 절차는 [팀원 계정으로 실행](#팀원-계정으로-실행)에 있다.
2026-11-18부터 perf 전용 기간이다. 종료일은 아직 정하지 않았다 ([perf 전용 기간](../README.md#perf-전용-기간)). 테스트가 끝나도 `finish`를 하지 않는다.

## 팀원 계정으로 실행

팀원 계정 이름은 `perf-<성>`이다. root 권한은 없고 아래 명령만 허용된다.

| 하는 일 | 명령 |
|---|---|
| 스크립트 실행 | `$R <명령>` |
| perf.env 수정 | `sudoedit /opt/restless/perf.env` |
| 서버 저장소 갱신 | `sudo -u ubuntu git -C /home/ubuntu/restless pull --ff-only` |
| 토큰 묶음 읽기 | `sudo -u ubuntu cat /home/ubuntu/restless/.ai-output/perf-tokens/seed-out.tar.gz` |
| 티켓팅 시드와 검증 | `$T seed`, `$T verify <공연ID>` |

`$R`은 `sudo -u ubuntu /home/ubuntu/restless/perf/ec2/perf-ec2.sh`, `$T`는 `sudo -u ubuntu /home/ubuntu/restless/perf/seed/ticketing.sh`이다. 접속하면 자동으로 잡히므로 직접 입력하지 않는다.
`perf/seed/ticketing.sh verify 1`은 `$T verify 1`로 쓴다. `$R verify`는 스케일을 받는 다른 검증이다.
저장소는 `/home/ubuntu/restless` 하나뿐이고 팀원은 `cd`로 들어갈 수 없다. 들어갈 필요도 없다.

### 처음 한 번

노트북에서 키를 만들고 공개키 한 줄을 EC2 담당자에게 보낸다. 개인키 `~/.ssh/fanq-perf`는 보내지 않는다.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/fanq-perf -C "<이름>"
cat ~/.ssh/fanq-perf.pub
```

`~/.ssh/config` 파일 맨 아래에 추가한다. 폴더가 아니라 파일이다. Windows는 `C:\Users\<사용자>\.ssh\config`이고 `.txt`가 붙으면 안 된다.

```
Host fanq-perf
  HostName 3.35.88.242
  User perf-<본인>
  IdentityFile ~/.ssh/fanq-perf
```

`ssh fanq-perf`로 접속해 `$R check`를 실행한다. `profile=perf db=perf-restless`가 나오면 준비가 끝났다.

### 매번 테스트할 때

1. 채널에 시작을 알린다. 다른 사람이 쓰는 중이면 기다린다. 서버와 DB가 하나라 동시에 하면 결과가 섞이고 서로의 테스트가 깨진다.
2. 서버 준비. `ssh fanq-perf`로 접속한 뒤 실행한다.

```bash
sudo -u ubuntu git -C /home/ubuntu/restless pull --ff-only
sudoedit /opt/restless/perf.env          # 필요한 값만 수정
$R check
$R stop-app; $R reset-db                 # DB 이름 입력
$R start sha-<태그> && $R stop-app
$R seed <스케일>
```

3. 토큰 받기. 노트북 저장소 폴더에서 실행한다.

```bash
mkdir -p perf/seed/out
ssh fanq-perf 'sudo -u ubuntu cat /home/ubuntu/restless/.ai-output/perf-tokens/seed-out.tar.gz' > perf/seed/out/seed-out.tar.gz
tar -xzf perf/seed/out/seed-out.tar.gz -C perf/seed/out && rm perf/seed/out/seed-out.tar.gz
```

4. 측정. 시작 시각과 종료 시각을 채널에 남긴다. EC2 담당자가 그 구간으로 CloudWatch 리포트를 뽑아 준다.

```bash
$R start sha-<태그> && $R lockstats      # 서버, 측정 전 값
# 노트북에서 k6 실행
$R lockstats                             # 서버, 측정 후 값
$R verify <스케일>                       # 서버
```

5. 인계. `sudoedit /opt/restless/perf.env`로 바꾼 값을 되돌리고 `$R check`를 실행한 뒤 채널에 끝을 알린다.

같은 조건으로 한 번 더 재려면 2번의 `stop-app; reset-db`부터 다시 한다. `finish`는 실행하지 않는다.

### 아래 런북 명령을 바꿔 쓰는 법

| 런북 | 팀원 |
|---|---|
| `cd ~/restless` | 쓰지 않는다 |
| `perf/ec2/perf-ec2.sh <명령>` | `$R <명령>` |
| `perf/seed/ticketing.sh <명령>` | `$T <명령>` |
| `sudo vi /opt/restless/perf.env` | `sudoedit /opt/restless/perf.env` |
| `git pull -q` | `sudo -u ubuntu git -C /home/ubuntu/restless pull --ff-only` |
| `serve-tokens`와 curl | 3번의 `ssh fanq-perf '...'` |

### 자주 막히는 곳

- `-u: command not found`: 메신저에서 복사한 `R=...` 줄의 따옴표가 바뀌었다. `R=...`는 입력하지 않는다. `exit` 후 다시 접속한다.
- `sudo su`나 다른 sudo 명령이 비밀번호를 묻는다: 허용되지 않은 명령이다. 계정에 비밀번호가 없어 통과할 수 없다. 위 표의 명령으로 바꾼다.
- `cd ~/restless`에서 No such file: 팀원 홈에는 저장소가 없다. `cd` 없이 `$R`을 쓴다.
- 접속이 안 된다: 공인 IP가 바뀌었을 수 있다. `curl -s https://checkip.amazonaws.com` 결과를 EC2 담당자에게 보낸다. 보안 그룹의 22, 8080, 8081 규칙을 바꿔야 한다.
- 따옴표가 들어간 명령은 메신저 코드 블록으로 주고받는다.

### 계정 정리 (EC2 담당자, perf 전용 기간 종료 시)

```bash
for U in $(getent group perf-runners | cut -d: -f4 | tr , " "); do sudo userdel -r "$U"; done
sudo rm /etc/sudoers.d/perf-runners /etc/sudoers.d/perf-runners-ticketing
sudo groupdel perf-runners
```

보안 그룹에서 팀원 IP의 22, 8080, 8081 규칙을 지운다. perf.env를 열 수 있었으므로 DB 비밀번호를 바꾼다.

## 명령

### `check`: 설정 확인
- `/opt/restless/perf.env`의 이름과 주요 값을 보여 준다. 비밀값은 출력하지 않는다. 프로필, DB 이름, 대기열, 가짜 PG 값을 본다.
- 프로필이 perf가 아니면 멈춘다. DB 이름이 `perf-`로 시작하지 않거나 prod가 들어 있어도 멈춘다. JWT 비밀값이 32바이트보다 짧아도 멈춘다.
- 아무것도 바꾸지 않는다.
- 언제: perf.env를 고친 직후, 테스트 시작 전.

### `start <태그>`: perf 앱 띄우기
1. `deploy.paused`를 만들어 자동 배포를 멈춘다.
2. 배포 컨테이너 `restless`와 기존 `restless-perf`를 지운다.
3. 태그 이미지를 받아 perf.env로 `restless-perf`를 띄운다. 포트는 8080, 8081이다.
4. health가 응답할 때까지 최대 180초 기다린다.
5. Flyway 결과를 보여 준다. 빈 DB면 이때 스키마가 만들어진다.

- 언제: 시드 전 스키마를 만들 때, 측정 직전.
- perf.env는 컨테이너를 만들 때만 읽힌다. perf.env를 바꿨거나 테스트할 코드가 바뀌어 새 태그가 필요하면 `start`를 다시 한다. 둘 다 그대로면 다시 할 필요가 없다.

### `stop-app`: perf 앱만 정지
- `restless-perf`만 멈춘다. 자동 배포는 멈춘 채로 둔다.
- 앱을 꺼야 하는 이유는 다음과 같다.
  - `reset-db`: 앱이 커넥션을 잡고 있으면 DB를 지울 수 없다.
  - `seed`: 앱 스케줄러가 시드 데이터를 건드리면 안 된다.
  - `serve-tokens`: 8080을 앱이 쓰고 있으면 토큰을 내보낼 수 없다.

### `seed <scale>`: 시드와 토큰
1. `perf/seed/scale.yml`의 스케일대로 빈 perf DB에 데이터를 넣는다. 스케일 예시는 sale, capacity다.
2. 회원별 서명 토큰을 만든다. `users.csv`, `staff.csv`, `manifest.json`이고 3일 뒤 만료된다.
3. 바로 `verify`로 시드를 점검한다.
4. 토큰 세 파일을 압축 하나로 묶는다.

- 전제: DB가 비어 있고 스키마가 있어야 한다. 그래서 `reset-db` → `start` → `stop-app`을 먼저 한다.

### `lockstats`: DB 잠금 대기 누적값
- MySQL 전체 행 잠금 통계를 보여 준다. 대기 횟수, 대기 시간 합, 최대 대기 시간, 현재 대기 수가 나온다.
- RDS가 켜진 뒤로 계속 쌓이는 값이다. 측정 전후 차이만 의미가 있다.
- 언제: k6 직전과 직후에 한 번씩.

### 함께 쓰는 명령

| 명령 | 하는 일 |
|---|---|
| `reset-db` | perf DB를 지우고 빈 DB로 다시 만든다. 앱이 꺼져 있어야 하고 DB 이름을 입력해 확인한다 |
| `serve-tokens` | 토큰 압축 파일을 8080으로 한 번만 내보낸다. 받으면 바로 끝나고 5분이 지나면 닫힌다 |
| `verify <scale>` | 불변식, 금액, FK를 검사한다. 부하 뒤 `seed baseline` 검사 실패는 정상이다 |
| `finish` | perf 앱을 지우고 자동 배포를 다시 켠다. 기간 중에는 하지 않는다 |

## 도메인 A → B → C 순서

공통 전제
- 같은 `perf-restless` DB를 쓰면 도메인마다 `reset-db`를 한다.
- 도메인끼리 시간이 겹치면 안 된다. 앱 컨테이너와 RDS가 하나라 결과가 섞인다.

### 기간 시작에 한 번 (콘솔)
- CPU 크레딧을 무제한으로 바꾸고 `CPUCreditBalance` 시작값을 적는다.
- EC2 세부 모니터링과 RDS 향상된 모니터링을 켠다.

### 각 도메인 차례

```bash
cd ~/restless && git pull -q

# 1. 내 실험 설정
sudo vi /opt/restless/perf.env          # 필요한 값만 변경
perf/ec2/perf-ec2.sh check

# 2. 빈 DB와 스키마
perf/ec2/perf-ec2.sh stop-app           # 앱이 없다는 에러가 나도 괜찮다
perf/ec2/perf-ec2.sh reset-db           # perf-restless 입력
perf/ec2/perf-ec2.sh start sha-<태그> && perf/ec2/perf-ec2.sh stop-app

# 3. 시드와 토큰
perf/ec2/perf-ec2.sh seed <내 도메인 스케일>
perf/ec2/perf-ec2.sh serve-tokens       # 담당자 노트북에서 curl로 받아 해당 팀원에게 개인 메시지로 보낸다
                                        # 팀원: tar -xzf <파일> -C perf/seed/out

# 4. 측정
perf/ec2/perf-ec2.sh start sha-<태그> && perf/ec2/perf-ec2.sh lockstats   # 측정 전 값
#    → 팀원 노트북에서 k6 실행
perf/ec2/perf-ec2.sh lockstats                                            # 측정 후 값
perf/ec2/perf-ec2.sh verify <스케일> | awk '$1=="check" && $NF!=0'
#    결제가 있는 시나리오면 reset 전에 결제 확인 쿼리를 돌린다

# 5. 같은 도메인을 한 번 더 재려면 2번부터 반복한다

# 6. 다음 도메인에 넘기기
sudo vi /opt/restless/perf.env          # 내가 바꾼 값 되돌리기. 대기열 true, 추가한 줄 삭제
perf/ec2/perf-ec2.sh check              # 앱은 그대로 둔다. finish 하지 않는다
```

노트북 쪽 토큰 받기

```bash
mkdir -p perf/seed/out && curl -fo perf/seed/out/seed-out.tar.gz http://perf-app:8080/seed-out.tar.gz
tar -xzf perf/seed/out/seed-out.tar.gz -C perf/seed/out && rm perf/seed/out/seed-out.tar.gz
```

### 도메인별로 달라지는 것

| | A (예: 드롭) | B (예: 경매) | C (예: 티켓팅) |
|---|---|---|---|
| perf.env | 대기열, TTL | 각자 필요한 값 | 각자 필요한 값 |
| 스케일 | sale, capacity | base 등 | base 등 |
| 태그 | 각자 고정 | 각자 고정 | 각자 고정 |

### 기간 종료

```bash
perf/ec2/perf-ec2.sh finish             # 3분 안에 배포 컨테이너가 다시 뜬다
```

콘솔에서 CPU 크레딧을 표준으로 되돌리고 `CPUCreditBalance` 끝값을 적는다. 모니터링을 끈다.

## 자주 하는 실수

- `perf-ec2.sh: command not found`: `~/restless`에서 `perf/ec2/perf-ec2.sh`로 실행한다. 폴더 안에서는 `./perf-ec2.sh`처럼 `./`를 붙인다.
- 재접속한 EC2 터미널은 `~`에서 시작한다. 먼저 `cd ~/restless`를 한다.
- `serve-tokens` 뒤 앱이 꺼진 채로 k6를 돌리면 연결이 거부된다. 측정 전에 `start`를 한다.
- 측정 전 `lockstats`를 빠뜨리면 차이를 구할 수 없다. `start && lockstats`를 한 줄로 친다.
- 결제 확인 쿼리는 `reset-db` 전에 돌린다. 지우고 나면 확인할 수 없다.
