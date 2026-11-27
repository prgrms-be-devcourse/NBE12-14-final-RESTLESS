"""Local HTTP stub checks for ticketing k6 scenarios, the runner and manual refresh behavior."""
import csv
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(os.environ.get('TICKETING_SCENARIO_ROOT', Path(__file__).resolve().parents[4]))


class Handler(BaseHTTPRequestHandler):
    mismatch = False
    invalid_availability = False
    reject_token = None
    seat_requests = 0

    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.reject_token and self.headers.get('Authorization') == f'Bearer {self.reject_token}':
            self.send_error(401)
            return
        if self.path == '/actuator/prometheus':
            body = 'up 1\n'
        elif self.path.endswith('/booking-availability'):
            time.sleep(0.2)
            body = json.dumps({} if self.invalid_availability else dict(maximum=3, confirmed=0, reserved=0, remaining=3, activeReservation=None))
        elif self.path.endswith('/seats/status'):
            body = json.dumps([dict(seatId=2 if self.mismatch else 1, status='AVAILABLE')])
        elif self.path.endswith('/seats'):
            Handler.seat_requests += 1
            body = json.dumps([dict(seatId=1, status='AVAILABLE')])
        else:
            self.send_error(404)
            return
        payload = body.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        payload = b'{"reservationId":1}'
        self.send_response(201)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class ScenarioTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def run_scenario(self, scenario, refresh=0, mismatch=False, invalid_availability=False, entry='simultaneous',
                     reject_token=None, expect_failure=False):
        Handler.mismatch = mismatch
        Handler.invalid_availability = invalid_availability
        Handler.reject_token = reject_token
        Handler.seat_requests = 0
        with tempfile.TemporaryDirectory() as tmp:
            users = Path(tmp) / 'users.csv'
            with users.open('w', newline='') as f:
                writer = csv.writer(f)
                writer.writerows([['user_id', 'access_token'], ['1', 'fake-1'], ['2', 'fake-2']])
            output = Path(tmp) / 'summary.json'
            env = dict(os.environ, BASE_URL=f'http://127.0.0.1:{self.server.server_port}',
                       MANAGEMENT_URL=f'http://127.0.0.1:{self.server.server_port}', PERFORMANCE_ID='1',
                       PARTICIPANTS='1' if scenario == 'mixed' else '2', HOLD_PARTICIPANTS='1', VUS='2',
                       USERS_CSV=str(users), SETUP='csv', RUN_ID='scenario-test', POLL_ENTRY=entry,
                       REFRESH_COUNT=str(refresh), REFRESH_INTERVAL_SECONDS='1', HOLD_DELAY_SECONDS='1',
                       SUMMARY_PATH=str(output))
            result = subprocess.run(['k6', 'run', '--quiet', str(ROOT / 'perf/scenarios/ticketing' / (scenario + '.js'))],
                                    env=env, capture_output=True, text=True, timeout=20)
            if invalid_availability or expect_failure:
                self.assertNotEqual(result.returncode, 0, result.stderr)
            else:
                self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(output.read_text())

    def test_page_refresh_and_safe_summary(self):
        report = self.run_scenario('page', refresh=1)
        metrics = report['metrics']
        self.assertEqual(metrics['ticket_poll_duration{phase:entry}']['values']['count'], 2)
        self.assertEqual(metrics['ticket_refresh_requests']['values']['count'], 2)
        self.assertEqual(metrics['ticket_availability_requests']['values']['count'], 2)
        page = metrics['ticket_page_entry_duration{outcome:ok}']['values']
        self.assertEqual(page['count'], 2)
        self.assertGreaterEqual(page['avg'], 150)
        self.assertGreater(metrics['iteration_duration']['values']['avg'] - page['avg'], 800)
        self.assertEqual(report['setup_data']['users'], [{'id': '1'}, {'id': '2'}])

    def test_page_failure_is_recorded_separately(self):
        metrics = self.run_scenario('page', invalid_availability=True)['metrics']
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:error}']['values']['count'], 2)
        self.assertEqual(metrics['ticket_availability_errors']['values']['passes'], 2)

    def test_page_duration_excludes_spread_delay(self):
        metrics = self.run_scenario('page', entry='spread')['metrics']
        page = metrics['ticket_page_entry_duration{outcome:ok}']['values']
        self.assertEqual(page['count'], 2)
        self.assertGreater(metrics['iteration_duration']['values']['avg'] - page['avg'], 400)

    def test_no_automatic_polling_and_mixed_hold(self):
        for scenario, count in [('simultaneous', 2), ('mixed', 1)]:
            with self.subTest(scenario=scenario):
                metrics = self.run_scenario(scenario)['metrics']
                self.assertEqual(metrics['ticket_poll_requests']['values']['count'], count)
                self.assertNotIn('ticket_refresh_requests', metrics)
                if scenario == 'mixed':
                    self.assertEqual(metrics['ticket_hold_attempts']['values']['count'], 1)

    def test_mixed_readers_enter_like_page(self):
        metrics = self.run_scenario('mixed')['metrics']
        self.assertEqual(metrics['ticket_availability_requests']['values']['count'], 1)
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:ok}']['values']['count'], 1)
        self.assertGreaterEqual(metrics['ticket_page_entry_duration{outcome:ok}']['values']['avg'], 150)
        self.assertEqual(metrics['ticket_start_offset{phase:hold}']['values']['count'], 1)
        self.assertGreaterEqual(metrics['ticket_start_offset{phase:hold}']['values']['avg'], 900)  # HOLD_DELAY_SECONDS=1

    def test_start_offset_shows_simultaneous_and_spread_entry(self):
        simultaneous = self.run_scenario('simultaneous')['metrics']['ticket_start_offset{phase:entry}']['values']
        self.assertEqual(simultaneous['count'], 2)
        self.assertLess(simultaneous['max'], 500)
        spread = self.run_scenario('page', entry='spread')['metrics']['ticket_start_offset{phase:entry}']['values']
        self.assertEqual(spread['count'], 2)
        self.assertGreater(spread['max'], 1200)  # 2명이면 0초와 1.5초에 출발한다.

    def test_hold_scenarios_start_together(self):
        metrics = self.run_scenario('hot')['metrics']
        offset = metrics['ticket_start_offset{phase:hold}']['values']
        self.assertEqual(offset['count'], 2)
        self.assertLess(offset['max'], 500)
        self.assertEqual(metrics['ticket_hold_completed']['values']['count'], 2)

    def test_unauthorized_seat_response_fails_run(self):
        metrics = self.run_scenario('simultaneous', reject_token='fake-2', expect_failure=True)['metrics']
        self.assertEqual(metrics['ticket_poll_errors']['values']['passes'], 1)

    def test_runner_maps_scenarios_and_rejects_invalid_input(self):
        port = self.server.server_port
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / 'fake-k6'
            fake.write_text('#!/usr/bin/env python3\nimport json,os,sys\nprint(json.dumps({"SCRIPT":sys.argv[-1], '
                            '**{k:os.environ.get(k) for k in ["MODE","POLL_ENTRY","PARTICIPANTS","VUS","HOLD_PARTICIPANTS","MIXED_HOLD"]}}))\n')
            fake.chmod(0o700)
            users = Path(tmp) / 'users.csv'
            users.write_text('user_id,access_token\n' + ''.join(f'{n},runner-{n}\n' for n in range(1, 4001)))
            script = str(ROOT / 'perf/scenarios/run-ticketing.sh')
            cases = {'simultaneous': ('poll', None), 'page': ('page', 'spread'), 'hot': ('hot', None),
                     'overlap': ('overlap', None), 'distributed': ('distributed', None), 'replay': ('replay', None),
                     'mixed': ('mixed', 'spread'), 'mixed-distributed': ('mixed', 'spread')}
            for scenario, (mode, entry) in cases.items():
                with self.subTest(scenario=scenario):
                    # 계정 장부가 서버 주소별로 기록되므로 실행마다 다른 주소를 써서 서로 영향을 주지 않게 한다.
                    base = f'http://127.0.0.1:{port}'
                    env = dict(os.environ, BASE_URL=base, MANAGEMENT_URL=base, PERF_ALLOWED_APP_HOSTS='127.0.0.1',
                               PERFORMANCE_ID=str(time.time_ns() % 1_000_000_000 + 1), USERS_CSV=str(users),
                               K6_BIN=str(fake), K6_WEB_DASHBOARD='false')
                    env.pop('POLL_ENTRY', None)
                    result = subprocess.run([script, scenario, '100'], env=env, capture_output=True, text=True, timeout=30)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    data = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{"SCRIPT"')))
                    self.assertTrue(data['SCRIPT'].endswith(f'/ticketing/{scenario}.js'))
                    self.assertEqual(data['MODE'], mode)
                    self.assertEqual(data['POLL_ENTRY'], entry)
                    self.assertEqual(data['PARTICIPANTS'], '80' if mode == 'mixed' else '100')
                    self.assertEqual(data['HOLD_PARTICIPANTS'], '20' if mode == 'mixed' else None)
                    self.assertEqual(data['MIXED_HOLD'], 'distributed' if scenario == 'mixed-distributed' else 'hot')
            for args in (['spread', '100'], ['burst', '100'], ['hot', '0'], ['mixed', '1'], []):
                with self.subTest(args=args):
                    env = dict(os.environ, BASE_URL=f'http://127.0.0.1:{port}', MANAGEMENT_URL=f'http://127.0.0.1:{port}',
                               PERF_ALLOWED_APP_HOSTS='127.0.0.1', PERFORMANCE_ID='1', USERS_CSV=str(users), K6_BIN=str(fake))
                    self.assertNotEqual(subprocess.run([script, *args], env=env, capture_output=True).returncode, 0)

    def test_mismatched_states_trigger_full_reload(self):
        report = self.run_scenario('page', refresh=1, mismatch=True)
        self.assertEqual(report['metrics']['ticket_refresh_requests']['values']['count'], 2)
        self.assertEqual(Handler.seat_requests, 5)  # setup + two entries + two fallbacks
        self.assertEqual(report['metrics']['ticket_unexpected']['values']['passes'], 0)


if __name__ == '__main__':
    unittest.main()
