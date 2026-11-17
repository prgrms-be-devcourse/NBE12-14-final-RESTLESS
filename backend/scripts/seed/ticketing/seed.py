"""공유 layout.json의 좌석을 로컬 compose MySQL에 추가한다."""
import json
import math
from pathlib import Path
import subprocess


def seed_sql():
    directory = Path(__file__).resolve().parent
    layout = json.loads((directory / 'layout.json').read_text())
    sections = {section['code'] for section in layout['sections']}
    seats = layout['seats']
    positions = {(s['sectionCode'], s['rowLabel'], s['seatNumber']) for s in seats}
    if len(seats) != 100 or len(positions) != len(seats):
        raise ValueError('샘플은 중복 없는 100석이어야 합니다.')
    for seat in seats:
        if seat['sectionCode'] not in sections or not all(math.isfinite(seat[k]) for k in ('x', 'y')):
            raise ValueError('구역과 좌표를 확인하세요.')
    # HEX 리터럴로 전달해 JSON 문자열의 SQL 인용·역슬래시 해석을 피한다.
    encoded = json.dumps(layout, ensure_ascii=False).encode().hex()
    return f'SET @sample_layout_json = CONVERT(0x{encoded} USING utf8mb4);\n' + (directory / 'seed.sql').read_text()


if __name__ == '__main__':
    repo = Path(__file__).resolve().parents[4]
    subprocess.run([
        'docker', 'compose', '-f', str(repo / 'backend/compose.yaml'), '--profile', 'dev',
        'exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 --batch -u"$MYSQL_USER" "$MYSQL_DATABASE"',
    ], input=seed_sql(), text=True, check=True)
