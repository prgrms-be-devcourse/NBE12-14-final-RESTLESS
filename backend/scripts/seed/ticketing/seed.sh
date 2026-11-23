#!/usr/bin/env bash
set -euo pipefail

seed_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 호스트/DB 인수를 받지 않고 저장소의 로컬 개발 compose MySQL만 사용한다.
# mysql batch 기본 동작은 첫 SQL 오류에서 종료한다. --force를 사용하지 않는다.
# 따라서 COMMIT 전 오류가 나면 연결 종료 시 전체 DML이 롤백된다.
exec python3 "$seed_dir/seed.py"
