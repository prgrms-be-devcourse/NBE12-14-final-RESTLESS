#!/usr/bin/env bash
# 부하 테스트 DB 시드. 사용법은 perf/seed/README.md.
#
#   seed.sh --scale base --profile perf            # 빈 스키마에 시드, 토큰 CSV, 점검
#   seed.sh --scale base --profile perf --refresh  # 스냅샷 복원 뒤: 핫 판매 기간, 토큰 만료 갱신
#   seed.sh --scale base --profile perf --verify   # 점검만
#
# 필요한 환경변수: DB_HOST, DB_NAME, DB_USERNAME, DB_PASSWORD, JWT_SECRET, PERF_ALLOWED_DB_HOSTS
# 선택: DB_PORT(3306), MYSQL(mysql 클라이언트 명령. 예: "docker exec -i -e MYSQL_PWD perf-mysql mysql")
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
OUT="$ROOT/out"
DROP_INVARIANTS="$REPO/backend/src/test/resources/harness/drop-invariants.sql"
# 모든 시드 계정의 비밀번호 perf-password의 BCrypt 해시 (cost 10).
PASSWORD_HASH='$2y$10$7tegSVACM8q6z1UY7oci0OchPPdpSxL9w1qLDYRKeyb4kolp1B1Ki'

die() { echo "seed: $*" >&2; exit 1; }

SCALE="" PROFILE="" MODE="seed"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scale) SCALE="${2:-}"; shift 2 ;;
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --refresh) MODE="refresh"; shift ;;
    --verify) MODE="verify"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$SCALE" ]] || die "--scale is required (small, base, large)"

# ---------------------------------------------------------------- 안전장치
# 1. 부하 테스트 프로필에서만 실행한다. 앱의 application-perf.yml과 같은 이름이다.
[[ "$PROFILE" == "perf" ]] || die "--profile perf is required. Seed never runs for other profiles."
: "${DB_HOST:?DB_HOST is required}" "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}" "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${PERF_ALLOWED_DB_HOSTS:?PERF_ALLOWED_DB_HOSTS is required (comma separated)}"
DB_PORT="${DB_PORT:-3306}"
MYSQL="${MYSQL:-mysql}"

# 2. 허용 목록에 정확히 있는 호스트만 쓴다.
allowed=0
IFS=',' read -ra hosts <<< "$PERF_ALLOWED_DB_HOSTS"
for host in "${hosts[@]}"; do
  [[ "$(echo "$host" | xargs)" == "$DB_HOST" ]] && allowed=1
done
[[ $allowed -eq 1 ]] || die "DB_HOST $DB_HOST is not in PERF_ALLOWED_DB_HOSTS"

# 3. 운영으로 보이는 이름은 허용 목록에 있어도 막는다.
shopt -s nocasematch
[[ "$DB_HOST $DB_NAME" =~ prod ]] && die "DB_HOST or DB_NAME looks like production: $DB_HOST/$DB_NAME"
shopt -u nocasematch

export MYSQL_PWD="$DB_PASSWORD"
# shellcheck disable=SC2086 # MYSQL may be a multi-word command
mysql_run() { $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME" "$@"; }
query() { mysql_run --batch --raw --skip-column-names -e "$1"; }

table_exists() {
  query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$1'"
}

# 4. Flyway로 만든 스키마여야 한다. 시드 모드는 비어 있어야 하고, 나머지 모드는 시드한 DB여야 한다.
for table in flyway_schema_history users drop_reservations payments; do
  [[ "$(table_exists "$table")" == "1" ]] || die "table $table is missing. Start the app once (perf profile) so Flyway migrates the schema."
done
if [[ "$MODE" == "seed" ]]; then
  [[ "$(table_exists perf_seed_meta)" == "0" ]] || die "already seeded. Restore the empty snapshot or recreate the schema first."
  [[ "$(query 'SELECT COUNT(*) FROM users')" == "0" ]] || die "users is not empty. Seed only runs on an empty schema."
else
  [[ "$(table_exists perf_seed_meta)" == "1" ]] || die "perf_seed_meta is missing: this DB was not seeded by perf/seed."
  seeded_scale="$(query "SELECT v FROM perf_seed_meta WHERE k = 'scale'")"
  [[ "$seeded_scale" == "$SCALE" ]] || die "DB was seeded with --scale $seeded_scale, not $SCALE"
fi

# ---------------------------------------------------------------- 수치
scale_vars="$(python3 "$ROOT/lib/scale.py" "$ROOT/scale.yml" "$SCALE")" || die "cannot read scale.yml"
eval "$scale_vars"
participants=$(( SCALE_drop_hot_stock * SCALE_contention_ratio ))
per_drop=$(( (SCALE_drop_bg_reservations + SCALE_drop_bg_drops - 1) / SCALE_drop_bg_drops ))
(( participants <= SCALE_users )) || die "hot participants $participants (stock x ratio) exceed users $SCALE_users"
(( per_drop <= SCALE_users )) || die "reservations per background drop $per_drop exceed users"
(( SCALE_drop_hot_queue_prefill <= SCALE_users )) || die "drop_hot_queue_prefill exceeds users"
(( SCALE_drop_bg_pct_confirmed + SCALE_drop_bg_pct_restored + SCALE_drop_bg_pct_released <= 100 )) \
  || die "drop_bg_pct_* sum exceeds 100"
(( SCALE_drop_hot_variants > 0 && SCALE_drop_bg_drops > 0 && SCALE_drop_bg_variants_per_drop > 0 )) \
  || die "variant and drop counts must be positive"

seq_size=$SCALE_users
for n in $(( SCALE_users + 2 )) "$SCALE_drop_bg_reservations" \
         $(( SCALE_drop_bg_drops * SCALE_drop_bg_variants_per_drop )) "$SCALE_drop_hot_queue_prefill"; do
  (( n > seq_size )) && seq_size=$n
done

session_header() {
  cat <<SQL
SET @scale = '$SCALE', @salt = '$1', @t0 = UTC_TIMESTAMP(6), @password_hash = '$PASSWORD_HASH';
SET @users = $SCALE_users, @ratio = $SCALE_contention_ratio, @seq_size = $seq_size;
SET @token_ttl_hours = $SCALE_token_ttl_hours, @hot_window_hours = $SCALE_hot_window_hours;
SET @hot_variants = $SCALE_drop_hot_variants, @hot_price = $SCALE_drop_hot_price;
SET @hot_stock = $SCALE_drop_hot_stock, @hot_queue_prefill = $SCALE_drop_hot_queue_prefill;
SET @bg_drops = $SCALE_drop_bg_drops, @bg_vpd = $SCALE_drop_bg_variants_per_drop, @bg_price = $SCALE_drop_bg_price;
SET @bg_reservations = $SCALE_drop_bg_reservations;
SET @pct_confirmed = $SCALE_drop_bg_pct_confirmed, @pct_restored = $SCALE_drop_bg_pct_restored;
SET @pct_released = $SCALE_drop_bg_pct_released;
SQL
}

run_files() {
  local salt="$1"; shift
  {
    session_header "$salt"
    echo "SET SESSION FOREIGN_KEY_CHECKS = 0;"
    for file in "$@"; do
      echo "SELECT CONCAT(DATE_FORMAT(UTC_TIMESTAMP(), '%H:%i:%s'), '  $(basename "$file")');"
      cat "$file"
      echo
    done
    echo "SET SESSION FOREIGN_KEY_CHECKS = 1;"
  } | mysql_run --batch --skip-column-names | awk -F'\t' '$2 != "analyze"'
}

write_tokens() {
  [[ -n "${JWT_SECRET:-}" ]] || die "JWT_SECRET is required (same value as the perf app)"
  mkdir -p "$OUT" && chmod 700 "$OUT"
  python3 "$ROOT/lib/tokens.py" --users "$SCALE_users" --salt "$1" \
    --ttl-hours "$SCALE_token_ttl_hours" --out "$OUT"
  query "SELECT JSON_PRETTY(JSON_OBJECT(
      'scale', (SELECT v FROM perf_seed_meta WHERE k = 'scale'),
      'seeded_at', (SELECT v FROM perf_seed_meta WHERE k = 'seeded_at'),
      'contention_ratio', $SCALE_contention_ratio,
      'participants', 'users.csv rows 1..$participants',
      'drop', JSON_OBJECT(
          'hot_drop_id', 1,
          'opens_at', (SELECT opens_at FROM drops WHERE id = 1),
          'closes_at', (SELECT closes_at FROM drops WHERE id = 1),
          'total_stock', $SCALE_drop_hot_stock,
          'variants', (SELECT JSON_ARRAYAGG(JSON_OBJECT('variant_id', v.id, 'sku', v.sku_code,
                                                        'price', v.price_amount, 'total_quantity', i.total_quantity))
                       FROM drop_variants v JOIN drop_inventories i ON i.variant_id = v.id
                       WHERE v.drop_id = 1))))" > "$OUT/manifest.json"
  echo "out: $OUT/users.csv, staff.csv, manifest.json"
}

verify() {
  local result
  result="$({
    cat "$ROOT/verify.sql"
    # drop-invariants.sql의 "-- name:" 블록을 (kind, name, value) 한 행씩으로 바꿔 실행한다.
    awk '
      /^-- name: / { name = substr($0, 10); q = ""; next }
      /^--/ { next }
      name != "" {
        q = q " " $0
        if ($0 ~ /;[[:space:]]*$/) {
          sub(/;[[:space:]]*$/, "", q); gsub(/\047/, "\047\047", name)
          printf "SELECT \047check\047, \047drop %s\047, (%s);\n", name, q
          name = ""
        }
      }' "$DROP_INVARIANTS"
  } | mysql_run --batch --skip-column-names)"
  echo "$result" | column -t -s $'\t'
  local failed
  failed="$(echo "$result" | awk -F'\t' '$1 == "check" && $3 != 0' | wc -l | xargs)"
  [[ "$failed" == "0" ]] || die "$failed check(s) failed"
  echo "verify: all checks passed"
}

# ---------------------------------------------------------------- 실행
started=$(date +%s)
case "$MODE" in
  seed)
    [[ -n "${JWT_SECRET:-}" ]] || die "JWT_SECRET is required (same value as the perf app)"
    salt="$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
    echo "seed: scale=$SCALE users=$SCALE_users hot_stock=$SCALE_drop_hot_stock participants=$participants"
    run_files "$salt" "$ROOT"/sql/[0-9][0-9]_*.sql
    write_tokens "$salt"
    verify
    ;;
  refresh)
    salt="$(query "SELECT v FROM perf_seed_meta WHERE k = 'salt'")"
    run_files "$salt" "$ROOT/sql/99_hot_window.sql"
    write_tokens "$salt"
    ;;
  verify)
    verify
    ;;
esac
echo "seed: $MODE done in $(( $(date +%s) - started ))s"
