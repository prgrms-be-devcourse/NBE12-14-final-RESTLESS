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
    checkout_status = 200
    checkout_delay = 0
    posts = []
    seat_auth = []
    seat_requests = 0
    short_after = None

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
            Handler.seat_auth.append(self.headers.get('Authorization'))
            # short_seats: 준비 요청(첫 요청) 뒤로는 비정상적으로 짧은 본문을 준다
            body = '[]' if Handler.short_after is not None and Handler.seat_requests > Handler.short_after else json.dumps([dict(seatId=1, status='AVAILABLE')])
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
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        Handler.posts.append((self.path, self.headers.get('Idempotency-Key'), body))
        status = 201
        if self.path.endswith('/checkout'):
            time.sleep(Handler.checkout_delay)
            status = Handler.checkout_status
            payload = (json.dumps(dict(reservationId=1, orderId=7, totalAmount=1000, checkoutStartedAt='2026-11-27T00:00:00Z',
                                       paymentExpiresAt='2026-11-27T00:05:00Z')) if status == 200
                       else json.dumps(dict(code='RESERVATION_CANNOT_CHECKOUT'))).encode()
        elif self.path == '/api/payments':
            payload = json.dumps(dict(paymentId=3, pgOrderId='PAY-1', amount=1000, orderName='ticket',
                                      expiresAt='2026-11-27T00:05:00Z', status='READY', created=True)).encode()
        else:
            payload = b'{"reservationId":1}'
        self.send_response(status)
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
                     reject_token=None, expect_failure=False, participants=None, extra_env=None, checkout_status=200, checkout_delay=0, user_count=2, short_seats=False, short_after=None):
        Handler.mismatch = mismatch
        Handler.invalid_availability = invalid_availability
        Handler.reject_token = reject_token
        Handler.checkout_status = checkout_status
        Handler.checkout_delay = checkout_delay
        Handler.posts = []
        Handler.seat_auth = []
        Handler.short_after = 1 if short_seats else short_after
        Handler.seat_requests = 0
        with tempfile.TemporaryDirectory() as tmp:
            users = Path(tmp) / 'users.csv'
            with users.open('w', newline='') as f:
                writer = csv.writer(f)
                writer.writerows([['user_id', 'access_token']] + [[str(n), f'fake-{n}'] for n in range(1, user_count + 1)])
            output = Path(tmp) / 'summary.json'
            env = dict(os.environ, BASE_URL=f'http://127.0.0.1:{self.server.server_port}',
                       MANAGEMENT_URL=f'http://127.0.0.1:{self.server.server_port}', PERFORMANCE_ID='1',
                       PARTICIPANTS=participants or ('1' if scenario == 'mixed' else '2'), HOLD_PARTICIPANTS='1', VUS=participants or '2',
                       USERS_CSV=str(users), SETUP='csv', RUN_ID='scenario-test', POLL_ENTRY=entry,
                       REFRESH_COUNT=str(refresh), REFRESH_INTERVAL_SECONDS='1',
                       SUMMARY_PATH=str(output), **(extra_env or {}))
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
        hold_start = metrics['ticket_start_offset{phase:hold}']['values']
        self.assertEqual(hold_start['count'], 1)
        # 선점자는 조회자와 함께 출발해 조회 응답이 끝나기 전에 선점을 보낸다.
        self.assertLess(hold_start['max'], metrics['ticket_entry_end_offset']['values']['max'])

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

    def test_replay_reports_first_and_repeated_requests_separately(self):
        metrics = self.run_scenario('replay', participants='1')['metrics']  # 스텁 좌석이 1석이다.
        self.assertEqual(metrics['ticket_hold_duration{outcome:created}']['values']['count'], 1)
        self.assertEqual(metrics['ticket_hold_duration{outcome:replay_ok}']['values']['count'], 1)

    def test_scheduled_start_waits_and_measures_from_start_time(self):
        metrics = self.run_scenario('simultaneous', extra_env={'START_DELAY_SECONDS': '2'})['metrics']
        self.assertGreaterEqual(metrics['iteration_duration']['values']['avg'], 1500)
        self.assertLess(metrics['ticket_start_offset{phase:entry}']['values']['max'], 500)

    def test_scheduled_start_is_rejected_for_hold_scenarios(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, BASE_URL='http://127.0.0.1:1', MANAGEMENT_URL='http://127.0.0.1:1', PERFORMANCE_ID='1',
                       PARTICIPANTS='2', VUS='2', USERS_CSV=str(Path(tmp) / 'none.csv'), RUN_ID='scheduled-hot',
                       START_DELAY_SECONDS='2')
            result = subprocess.run(['k6', 'run', '--quiet', str(ROOT / 'perf/scenarios/ticketing/hot.js')],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('START_DELAY_SECONDS', result.stderr)

    def test_checkout_runs_hold_checkout_and_payment_prepare_without_approval(self):
        metrics = self.run_scenario('checkout', participants='1')['metrics']  # 스텁 좌석이 1석이다.
        self.assertEqual(metrics['ticket_outcomes{outcome:prepared}']['values']['count'], 1)
        for name in ('ticket_checkout_duration{outcome:ok}', 'ticket_payment_prepare_duration{outcome:ok}',
                     'ticket_journey_duration{outcome:ok}'):
            self.assertEqual(metrics[name]['values']['count'], 1, name)
        paths = [path for path, _, _ in Handler.posts]
        self.assertEqual(paths, ['/api/ticket-reservations', '/api/ticket-reservations/1/checkout', '/api/payments'])
        self.assertNotIn('/api/payments/confirm', paths)
        prepare_key, prepare_body = Handler.posts[2][1], json.loads(Handler.posts[2][2])
        self.assertEqual(prepare_body, {'payableType': 'TICKET', 'payableId': 1})
        self.assertTrue(prepare_key and prepare_key != Handler.posts[0][1])

    def test_checkout_failure_stops_before_payment_and_fails_run(self):
        metrics = self.run_scenario('checkout', participants='1', checkout_status=409, expect_failure=True)['metrics']
        self.assertEqual(metrics['ticket_journey_duration{outcome:error}']['values']['count'], 1)
        self.assertNotIn('/api/payments', [path for path, _, _ in Handler.posts])

    def test_checkout_can_enter_seat_page_first(self):
        metrics = self.run_scenario('checkout', participants='1', extra_env={'CHECKOUT_PAGE_ENTRY': '1'})['metrics']
        self.assertEqual(metrics['ticket_outcomes{outcome:prepared}']['values']['count'], 1)
        self.assertEqual(metrics['http_reqs']['values']['count'], 7)  # 관리 확인 + 좌석 확인 + 화면 2 + 선점·checkout·결제 준비

    def test_checkout_interrupted_before_payment_fails_run(self):
        # checkout 응답이 완료 제한 시간(+gracefulStop)보다 늦으면 k6가 사용자를 중단한다. 이때 실행이 성공으로 끝나면 안 된다.
        metrics = self.run_scenario('checkout', participants='1', checkout_delay=4, expect_failure=True,
                                    extra_env={'MAX_DURATION': '1s', 'GRACEFUL_STOP': '0s'})['metrics']
        self.assertEqual(metrics['ticket_hold_completed']['values']['count'], 1)
        self.assertEqual(metrics['ticket_checkout_completed']['values']['count'], 0)

    def test_checkout_page_entry_failure_stops_before_hold(self):
        metrics = self.run_scenario('checkout', participants='1', invalid_availability=True,
                                    extra_env={'CHECKOUT_PAGE_ENTRY': '1'})['metrics']
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:error}']['values']['count'], 1)
        self.assertEqual(metrics['ticket_unexpected']['values']['passes'], 1)
        self.assertEqual(Handler.posts, [])

    def test_checkout_hold_start_is_recorded_after_page_entry(self):
        metrics = self.run_scenario('checkout', participants='1', extra_env={'CHECKOUT_PAGE_ENTRY': '1'})['metrics']
        entry = metrics['ticket_start_offset{phase:entry}']['values']['max']
        hold = metrics['ticket_start_offset{phase:hold}']['values']['max']
        self.assertGreaterEqual(hold - entry, 150)  # 구매 가능 정보 스텁이 200ms 걸린다.

    def test_arrival_rate_sends_one_distinct_user_per_iteration(self):
        report = self.run_scenario('page', participants='8', user_count=8,
                                   extra_env={'ARRIVAL_RATE': '4', 'ARRIVAL_SECONDS': '2'})
        metrics = report['metrics']
        self.assertEqual(metrics['ticket_poll_requests']['values']['count'], 8)
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:ok}']['values']['count'], 8)
        self.assertEqual(metrics['dropped_iterations']['values']['count'], 0)
        self.assertLess(metrics['ticket_arrival_lag']['values']['max'], 500)
        # setup의 좌석 확인 1번 + 사용자 8명이 서로 다른 토큰으로 1번씩
        self.assertEqual(len(set(Handler.seat_auth[1:])), 8)
        self.assertEqual(report['conditions']['entry'], 'arrival')
        self.assertGreaterEqual(metrics['iteration_duration']['values']['count'], 8)

    def test_arrival_rate_requires_users_to_match_rate_and_duration(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, BASE_URL='http://127.0.0.1:1', MANAGEMENT_URL='http://127.0.0.1:1', PERFORMANCE_ID='1',
                       PARTICIPANTS='7', VUS='7', USERS_CSV=str(Path(tmp) / 'none.csv'), RUN_ID='arrival-mismatch',
                       ARRIVAL_RATE='4', ARRIVAL_SECONDS='2')
            result = subprocess.run(['k6', 'run', '--quiet', str(ROOT / 'perf/scenarios/ticketing/page.js')],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('ARRIVAL_RATE x ARRIVAL_SECONDS', result.stderr)

    def test_page_reload_repeats_full_page_entry(self):
        report = self.run_scenario('page', participants='2', extra_env={'RELOAD_COUNT': '2', 'RELOAD_INTERVAL_SECONDS': '1'})
        metrics = report['metrics']
        self.assertEqual(metrics['ticket_page_reloads']['values']['count'], 4)
        self.assertEqual(metrics['ticket_page_reload_duration{outcome:ok}']['values']['count'], 4)
        self.assertEqual(metrics['ticket_availability_requests']['values']['count'], 6)
        # setup 1 + 첫 진입 2 + 다시 진입 4
        self.assertEqual(Handler.seat_requests, 7)
        self.assertEqual(report['conditions']['reloadCount'], 2)
        self.assertEqual(report['conditions']['reloadIntervalSeconds'], 1)

    def test_page_reload_counts_failed_reloads(self):
        # setup과 첫 진입 2번까지는 정상, 다시 진입부터 좌석 응답이 비정상
        metrics = self.run_scenario('page', participants='2', short_after=3, expect_failure=True,
                                    extra_env={'RELOAD_COUNT': '2', 'RELOAD_INTERVAL_SECONDS': '1'})['metrics']
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:ok}']['values']['count'], 2)
        self.assertEqual(metrics['ticket_page_reload_duration{outcome:error}']['values']['count'], 4)
        self.assertEqual(metrics['ticket_poll_errors']['values']['passes'], 4)

    def test_page_reload_with_arrival_and_light_check(self):
        metrics = self.run_scenario('page', participants='4', user_count=4,
                                    extra_env={'ARRIVAL_RATE': '2', 'ARRIVAL_SECONDS': '2', 'SEAT_CHECK': 'light',
                                               'RELOAD_COUNT': '1', 'RELOAD_INTERVAL_SECONDS': '1'})['metrics']
        self.assertEqual(metrics['ticket_page_reloads']['values']['count'], 4)
        self.assertEqual(metrics['ticket_page_reload_duration{outcome:ok}']['values']['count'], 4)
        self.assertEqual(metrics['dropped_iterations']['values']['count'], 0)

    def test_page_reload_rejects_unsupported_combinations(self):
        for script, extra, message in [
            ('simultaneous.js', {'RELOAD_COUNT': '1'}, 'RELOAD_COUNT is supported for page'),
            ('page.js', {'RELOAD_COUNT': '1', 'REFRESH_COUNT': '1'}, 'cannot be combined with REFRESH_COUNT'),
            ('page.js', {'RELOAD_COUNT': '101'}, 'RELOAD_COUNT must be an integer'),
        ]:
            with tempfile.TemporaryDirectory() as tmp:
                env = dict(os.environ, BASE_URL='http://127.0.0.1:1', MANAGEMENT_URL='http://127.0.0.1:1', PERFORMANCE_ID='1',
                           PARTICIPANTS='2', VUS='2', USERS_CSV=str(Path(tmp) / 'none.csv'), RUN_ID='reload-invalid', **extra)
                result = subprocess.run(['k6', 'run', '--quiet', str(ROOT / 'perf/scenarios/ticketing' / script)],
                                        env=env, capture_output=True, text=True, timeout=20)
                self.assertNotEqual(result.returncode, 0, script)
                self.assertIn(message, result.stderr)

    def test_light_seat_check_accepts_full_length_and_rejects_short_body(self):
        report = self.run_scenario('page', participants='8', user_count=8,
                                   extra_env={'ARRIVAL_RATE': '4', 'ARRIVAL_SECONDS': '2', 'SEAT_CHECK': 'light'})
        metrics = report['metrics']
        self.assertEqual(report['conditions']['seatCheck'], 'light')
        self.assertEqual(metrics['ticket_page_entry_duration{outcome:ok}']['values']['count'], 8)
        self.assertEqual(metrics['ticket_poll_errors']['values']['passes'], 0)
        metrics = self.run_scenario('page', participants='8', user_count=8, expect_failure=True, short_seats=True,
                                    extra_env={'ARRIVAL_RATE': '4', 'ARRIVAL_SECONDS': '2', 'SEAT_CHECK': 'light'})['metrics']
        self.assertEqual(metrics['ticket_poll_errors']['values']['passes'], 8)

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
            cases = {'simultaneous': ('poll', None), 'page': ('page', 'spread'), 'hot': ('hot', None), 'checkout': ('checkout', None),
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
