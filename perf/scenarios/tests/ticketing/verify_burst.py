import csv
import gzip
import http.server
import json
import os
import subprocess
import threading
import time
from pathlib import Path

root = Path.cwd()
out = root / '.ai-output/ticketing-k6'
out.mkdir(parents=True, exist_ok=True)
users = out / 'burst-users.csv'
with users.open('w', newline='') as stream:
    writer = csv.writer(stream)
    writer.writerow(['user_id', 'access_token'])
    writer.writerows((n, f'local-token-{n}') for n in range(1, 5))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        encoding = None
        if self.path == '/actuator/prometheus':
            body = b'probe'
        elif self.path == '/api/performances/1/seats':
            token = self.headers.get('Authorization', '')
            self.server.seen.append((time.monotonic(), token))
            if self.server.reject and token.endswith('-2'):
                self.send_error(401)
                return
            body = b'[{"seatId":1,"status":"HELD"}]'
            if self.headers.get('Accept-Encoding') == 'gzip' and self.server.gzip:
                body = gzip.compress(body)
                encoding = 'gzip'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        if encoding:
            self.send_header('Content-Encoding', encoding)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.seen = []
server.reject = False
server.gzip = True
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    url = f'http://127.0.0.1:{server.server_port}'
    summary = out / 'verify-burst.json'
    env = os.environ.copy()
    env.update(BASE_URL=url, MANAGEMENT_URL=url, PERFORMANCE_ID='1', USERS_CSV=str(users),
               PARTICIPANTS='4', VUS='4', RUN_ID='verify-burst', BURST_DELAY_SECONDS='2',
               SUMMARY_PATH=str(summary), K6_WEB_DASHBOARD='false', NO_PROXY='127.0.0.1,localhost')
    def run():
        return subprocess.run(['k6', 'run', '--quiet', str(root / 'perf/scenarios/ticketing/burst.js')],
                              env=env, capture_output=True, text=True, timeout=30)
    result = run()
    assert result.returncode == 0, result.stderr[-1500:]
    data = json.loads(summary.read_text())
    requests = server.seen[1:]  # setup performs one seat probe
    assert len(requests) == 4, len(requests)
    assert len({token for _, token in requests}) == 4
    assert max(t for t, _ in requests) - min(t for t, _ in requests) < 1
    assert data['metrics']['ticket_poll_requests']['values']['count'] == 4
    assert data['metrics']['ticket_burst_start_delay']['values']['count'] == 4
    assert 'local-token-' not in summary.read_text()
    env['SEAT_ENCODING'] = 'gzip'
    server.seen = []
    result = run()
    assert result.returncode == 0, result.stderr[-1500:]
    data = json.loads(summary.read_text())
    assert data['metrics']['ticket_seat_gzip_responses']['values']['count'] == 4
    assert data['metrics']['ticket_seat_encoding_errors']['values']['rate'] == 0
    assert data['conditions']['seatEncoding'] == 'gzip'
    server.gzip = False
    result = run()
    assert result.returncode != 0 and 'encoding mismatch' in result.stderr
    env['SEAT_ENCODING'] = 'identity'
    server.reject = True
    server.seen = []
    result = run()
    assert result.returncode != 0, '401 must fail the scenario threshold'
    assert len(server.seen) == 5
    print('burst: identity/gzip, gzip mismatch, synchronized requests, token-free summary, and 401 failure: passed')
finally:
    server.shutdown()
