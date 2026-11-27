"""Local HTTP stub checks for k6 entry and explicit manual refresh behavior."""
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
    seat_requests = 0

    def log_message(self, *_):
        pass

    def do_GET(self):
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

    def run_scenario(self, scenario, refresh=0, mismatch=False, invalid_availability=False, entry='simultaneous'):
        Handler.mismatch = mismatch
        Handler.invalid_availability = invalid_availability
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
            if invalid_availability:
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

    def test_mismatched_states_trigger_full_reload(self):
        report = self.run_scenario('page', refresh=1, mismatch=True)
        self.assertEqual(report['metrics']['ticket_refresh_requests']['values']['count'], 2)
        self.assertEqual(Handler.seat_requests, 5)  # setup + two entries + two fallbacks
        self.assertEqual(report['metrics']['ticket_unexpected']['values']['passes'], 0)


if __name__ == '__main__':
    unittest.main()
