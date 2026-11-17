# EC2 자동 배포

dev에 머지되면 [backend-image 워크플로](../../.github/workflows/backend-image.yml)가 GHCR에 `:dev` 이미지를 올린다.
EC2의 systemd 타이머가 3분마다 [restless-deploy.sh](restless-deploy.sh)를 실행해 이미지가 바뀌었을 때만 컨테이너를 교체한다.
EC2가 직접 가져오는 방식이라 GitHub에 AWS 키나 SSH 키를 두지 않고, 서버에 들어오는 포트도 더 열지 않는다.

## 동작

`:dev` 태그는 CI를 통과한 커밋 중 dev의 최신 커밋만 가리킨다. PR이 몰려 CI가 순서 없이 끝나도 옛 커밋으로 되돌아가지 않는다.

1. `:dev` 이미지를 받는다. 실행 중인 컨테이너와 같은 이미지면 아무것도 하지 않는다.
2. 다르면 지금 이미지를 `restless:previous`로 남기고 새 이미지로 컨테이너를 다시 띄운다. 교체하는 동안 약 30초간 응답하지 않는다.
3. 180초 안에 `/actuator/health`가 UP이 되지 않으면 직전 이미지로 되돌린다. 실패한 이미지는 기록해 두고, 새 커밋 이미지가 올라올 때까지 다시 시도하지 않는다.

교체하지 않는 경우:

- `/opt/restless/deploy.paused` 파일이 있을 때
- 부하 테스트 컨테이너 `restless-perf`가 실행 중일 때

## 설치

전제: [backend-image 워크플로](../../.github/workflows/backend-image.yml)가 올린 이미지를 받을 수 있게 EC2에서 `docker login ghcr.io`를 마쳤고, `/opt/restless/prod.env`가 있다.
`prod.env` 첫 줄은 `SPRING_PROFILES_ACTIVE=prod`다. 빠지면 dev 프로필의 공개된 로컬 JWT 키로 뜬다.

```bash
git clone https://github.com/prgrms-be-devcourse/NBE12-14-final-RESTLESS.git ~/restless   # 이미 있으면 git -C ~/restless pull
sudo install -m 755 ~/restless/deploy/ec2/restless-deploy.sh /opt/restless/
sudo install -m 644 ~/restless/deploy/ec2/restless-deploy.service ~/restless/deploy/ec2/restless-deploy.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now restless-deploy.timer
```

스크립트를 고친 뒤에는 `git pull`과 `install` 줄만 다시 실행한다.

## 확인과 운영

| 할 일 | 명령 |
|---|---|
| 다음 실행 시각 | `systemctl list-timers restless-deploy.timer` |
| 배포 기록 | `journalctl -u restless-deploy -n 50 --no-pager` |
| 지금 바로 실행 | `sudo systemctl start restless-deploy.service` |
| 자동 교체 멈춤 | `sudo touch /opt/restless/deploy.paused` |
| 다시 켬 | `sudo rm /opt/restless/deploy.paused` |
| 실행 중인 커밋 | `docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' restless` |

## 부하 테스트와 함께 쓰기

부하 테스트는 같은 서버의 8080을 쓰므로 배포 컨테이너를 멈추고 perf 컨테이너를 `restless-perf` 이름으로 띄운다.
아래 단계는 [perf/ec2/perf-ec2.sh](../../perf/ec2/perf-ec2.sh)의 `start`, `finish`가 대신한다.
그 이름의 컨테이너가 돌고 있는 동안 이 스크립트는 교체하지 않는다.

```bash
sudo touch /opt/restless/deploy.paused      # 멈추는 사이에 타이머가 배포 컨테이너를 다시 띄우지 않게 한다
docker rm -f restless
docker run -d --name restless-perf --env-file /opt/restless/perf.env -p 8080:8080 -p 8081:8081 \
  ghcr.io/prgrms-be-devcourse/nbe12-14-final-restless-backend:<실험할 태그>
# 테스트가 끝나면
docker rm -f restless-perf
sudo rm /opt/restless/deploy.paused         # 3분 안에 배포 컨테이너가 다시 뜬다
```
