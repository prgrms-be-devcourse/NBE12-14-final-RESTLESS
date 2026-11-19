import csv
import json
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

root = Path(__file__).resolve().parents[4]
scratch = root / '.ai-output'
scratch.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ticketing-allocator-', dir=scratch) as tmp:
    sandbox = Path(tmp)
    script = sandbox / 'perf/scenarios/ticketing/allocate-users.py'
    script.parent.mkdir(parents=True)
    shutil.copy(root / 'perf/scenarios/ticketing/allocate-users.py', script)
    csv_path = sandbox / 'users.csv'
    with csv_path.open('w', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow(['user_id', 'access_token'])
        writer.writerows((n, f'local-token-{n}') for n in range(1, 16))
    history = sandbox / '.ai-output/perf/ticketing-hot'
    history.mkdir(parents=True)
    (history / 'summary-before.json').write_text(json.dumps({
        'conditions': {'baseUrl': 'http://test:8080', 'performanceId': 1},
        'setup_data': {'users': [{'id': '1'}, {'id': '2'}]},
    }))

    def allocate(count, run_id, check=True):
        return subprocess.run(['python3', str(script), str(csv_path), str(count),
                               'http://test:8080', '1', run_id],
                              capture_output=True, text=True, check=check)

    assert allocate(3, 'first').stdout.strip() == '2'
    assert allocate(3, 'second').stdout.strip() == '5'
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda n: int(allocate(2, f'parallel-{n}').stdout), range(2)))
    assert sorted(results) == [8, 10], results
    assert allocate(10, 'insufficient', check=False).returncode != 0
    assert allocate(1, 'after-failure').stdout.strip() == '12'
print('allocation: history, sequential/concurrent use and exhaustion passed')
