#!/usr/bin/env bash
# 로컬 공통 시드. 로컬 개발 DB를 비우고 모든 도메인 시드를 정해진 순서로 넣는다.
# 몇 번을 실행해도, 누가 실행해도 같은 데이터가 된다. 사용법은 README.md를 본다.
#
#   bash backend/scripts/seed/seed.sh          확인을 묻고 실행
#   bash backend/scripts/seed/seed.sh --yes    묻지 않고 실행
#
# 대상은 저장소 backend/compose.yaml의 개발용 MySQL 컨테이너뿐이다. 호스트나 접속 정보를 받지 않는다.
# SEED_DB로 그 컨테이너 안의 다른 데이터베이스를 고를 수 있다 (기본 restless). 시드 자체를 시험할 때 쓴다.
set -euo pipefail

seed_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$seed_dir/../../.." && pwd)"
compose_file="$repo/backend/compose.yaml"
db="${SEED_DB:-restless}"

# 실행 순서. 앞 도메인이 만든 행을 뒤 도메인이 참조할 수 있다.
# 폴더나 seed.sql이 없는 도메인은 건너뛴다. 새 도메인은 폴더에 seed.sql을 두고 여기에 이름을 넣는다.
domains=(ticketing drop auction fanmeeting)

if [[ ! "$db" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "SEED_DB에는 영문, 숫자, _, -만 쓸 수 있습니다: $db" >&2
  exit 2
fi

run_mysql() {
  # mysql batch 모드는 첫 SQL 오류에서 멈춘다. --force를 쓰지 않는다.
  docker compose -f "$compose_file" --profile dev exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 --batch --skip-column-names -u"$MYSQL_USER" "$1"' \
    sh "$db"
}

if ! docker compose -f "$compose_file" --profile dev ps --status running --services 2>/dev/null | grep -qx mysql; then
  echo "개발용 MySQL이 꺼져 있습니다. backend/에서 ./gradlew bootRun을 한 번 실행하거나 docker compose --profile dev up -d mysql을 실행하세요." >&2
  exit 1
fi

if [[ "$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history';" | run_mysql)" != 1 ]]; then
  echo "${db}에 Flyway 스키마가 없습니다. backend/에서 ./gradlew bootRun을 한 번 실행해 테이블을 만든 뒤 다시 실행하세요." >&2
  exit 1
fi

if [[ "${1:-}" != --yes ]]; then
  echo "로컬 개발 DB($db)의 데이터를 모두 지우고 시드로 다시 채웁니다. 테이블 구조와 Flyway 기록은 그대로 둡니다."
  read -r -p "계속할까요? [y/N] " answer
  [[ "$answer" == y || "$answer" == Y ]] || { echo "취소했습니다."; exit 1; }
fi

echo "[reset] 데이터 비우기"
# TRUNCATE는 AUTO_INCREMENT도 처음으로 되돌린다. 그래서 자동 증가 ID를 쓰는 시드도 매번 같은 ID를 받는다.
truncates="$(echo "SELECT CONCAT('TRUNCATE TABLE \`', table_name, '\`;') FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history';" | run_mysql)"
printf 'SET FOREIGN_KEY_CHECKS = 0;\n%s\nSET FOREIGN_KEY_CHECKS = 1;\n' "$truncates" | run_mysql

echo "[common] 공통 계정"
run_mysql < "$seed_dir/common/users.sql"

for domain in "${domains[@]}"; do
  dir="$seed_dir/$domain"
  if [[ "$domain" == ticketing && -f "$dir/seed.py" ]]; then
    # 티켓팅 seed.sql은 seed.py가 layout.json을 변수로 붙여야 실행된다. 같은 함수로 SQL을 만들어 같은 DB에 넣는다.
    # 티켓팅 시드는 자동 증가 ID를 쓴다. 공유 테이블에서 티켓팅 구역(1000번대)을 받도록 시작값을 옮긴다.
    echo "[$domain]"
    echo "ALTER TABLE users AUTO_INCREMENT = 1000; ALTER TABLE venues AUTO_INCREMENT = 1000; ALTER TABLE concerts AUTO_INCREMENT = 1000;" | run_mysql
    python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import seed; sys.stdout.write(seed.seed_sql())' "$dir" | run_mysql
  elif [[ -f "$dir/seed.sql" ]]; then
    echo "[$domain]"
    run_mysql < "$dir/seed.sql" >/dev/null
  else
    echo "[$domain] 시드 없음, 건너뜀"
  fi
done

# 화면에서 새로 만든 행은 10000부터 받는다. 시드 ID 구역(10000 미만)과 겹치지 않는다.
# information_schema.tables의 auto_increment 값은 캐시라서 열 정의로 대상 테이블을 찾는다.
alters="$(echo "SELECT CONCAT('ALTER TABLE \`', table_name, '\` AUTO_INCREMENT = 10000;') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND extra LIKE '%auto_increment%' AND table_name <> 'flyway_schema_history';" | run_mysql)"
[[ -z "$alters" ]] || echo "$alters" | run_mysql

echo
echo "완료. 로그인 계정 (비밀번호 0000)"
echo "SELECT id, email, role FROM users WHERE id < 1000 ORDER BY id;" | run_mysql | column -t
echo
echo "드롭"
echo "SELECT id, status, title FROM drops ORDER BY id;" | run_mysql
echo
echo "경매"
echo "SELECT auction_id, status, seller_user_id, title FROM auctions ORDER BY auction_id;" | run_mysql
