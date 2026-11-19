#!/usr/bin/env python3
"""Reserve distinct CSV users for one local ticketing run."""
import csv
import json
import os
import sqlite3
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 6:
        sys.exit('usage: allocate-users.py CSV COUNT BASE_URL PERFORMANCE_ID RUN_ID')
    path, count_text, base, performance, run_id = sys.argv[1:]
    count = int(count_text)
    root = Path(__file__).resolve().parents[3]
    out = root / '.ai-output' / 'perf'
    out.mkdir(parents=True, exist_ok=True)
    with open(path, newline='', encoding='utf-8-sig') as source:
        reader = csv.DictReader(source)
        if not reader.fieldnames or not {'user_id', 'access_token'} <= set(reader.fieldnames):
            sys.exit('users.csv에 user_id와 access_token이 필요합니다.')
        ids = []
        for row in reader:
            user_id = (row.get('user_id') or '').strip()
            if not user_id or not (row.get('access_token') or '').strip():
                sys.exit('users.csv에 빈 사용자 ID 또는 토큰이 있습니다.')
            ids.append(user_id)
    if len(ids) != len(set(ids)):
        sys.exit('users.csv에 중복 사용자 ID가 있습니다.')
    db_path = out / 'ticketing-account-ledger.sqlite3'
    connection = sqlite3.connect(str(db_path), timeout=30, isolation_level=None)
    os.chmod(db_path, 0o600)
    connection.execute('PRAGMA busy_timeout=30000')
    connection.execute('CREATE TABLE IF NOT EXISTS used (base TEXT NOT NULL, performance TEXT NOT NULL, user_id TEXT NOT NULL, run_id TEXT NOT NULL, PRIMARY KEY (base, performance, user_id))')
    base = base.rstrip('/')
    connection.execute('BEGIN IMMEDIATE')
    try:
        # 예전 요약에 계정 정보가 있는 경우만 복구한다. 토큰은 읽지만 저장·출력하지 않는다.
        for summary in out.glob('**/summary*.json'):
            try:
                data = json.loads(summary.read_text(encoding='utf-8'))
                conditions = data.get('conditions') or {}
                if (str(conditions.get('baseUrl', '')).rstrip('/') != base or
                        str(conditions.get('performanceId', '')) != performance):
                    continue
                users = (data.get('setup_data') or {}).get('users') or []
                connection.executemany('INSERT OR IGNORE INTO used VALUES (?, ?, ?, ?)',
                    ((base, performance, str(u['id']), 'historical') for u in users if isinstance(u, dict) and u.get('id')))
            except (OSError, ValueError, TypeError, AttributeError):
                continue
        used = {row[0] for row in connection.execute('SELECT user_id FROM used WHERE base=? AND performance=?', (base, performance))}
        start = 0
        streak = 0
        for index, user_id in enumerate(ids):
            if user_id in used:
                streak = 0
            else:
                if streak == 0:
                    start = index
                streak += 1
                if streak == count:
                    break
        else:
            sys.exit('연속된 미사용 계정이 부족합니다. 새 시드 계정을 준비하세요.')
        connection.executemany('INSERT INTO used VALUES (?, ?, ?, ?)',
            ((base, performance, user_id, run_id) for user_id in ids[start:start + count]))
        connection.execute('COMMIT')
        print(start)
    except BaseException:
        connection.execute('ROLLBACK')
        raise
    finally:
        connection.close()


if __name__ == '__main__':
    main()
