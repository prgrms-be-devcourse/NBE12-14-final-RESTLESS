// 티켓팅 시나리오 공통 실행 로직. 시나리오 파일에서 종류를 지정한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';
import { runPoll } from './poll-logic.js';
import { runHold } from './hold-logic.js';
import { SharedArray } from 'k6/data';
import { Counter, Trend, Rate } from 'k6/metrics';

export function createRuntime(mode, fixedEntry = null, fixedMixedHold = null) {
const base = (__ENV.BASE_URL || '').replace(/\/$/, '');
const management = (__ENV.MANAGEMENT_URL || '').replace(/\/$/, '');
const allowed = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map(x => x.trim());
for (const value of [base, management]) {
  const match = /^https?:\/\/([^/:]+)(?::\d+)?$/.exec(value);
  if (!match || !/^perf-/i.test(match[1]) || /prod/i.test(match[1]) || !allowed.includes(match[1]))
    throw new Error('BASE_URL and MANAGEMENT_URL must use allowlisted perf- hosts');
}
function positive(name, fallback) {
  const n = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name} must be a positive integer`);
  return n;
}
if (!['poll', 'distributed', 'hot', 'overlap', 'replay', 'mixed'].includes(mode)) throw new Error('invalid MODE');
const performanceId = positive('PERFORMANCE_ID', 0);
const participants = positive('PARTICIPANTS', 100);
const vus = positive('VUS', mode === 'poll' ? participants : 50);
const hotSeats = positive('HOT_SEATS', 1);
const holdParticipants = mode === 'mixed' ? positive('HOLD_PARTICIPANTS', 20) : 0;
const holdDelay = mode === 'mixed' ? positive('HOLD_DELAY_SECONDS', 3) : 0;
const mixedHold = fixedMixedHold || __ENV.MIXED_HOLD || 'hot';
if (mode === 'mixed' && !['hot', 'distributed'].includes(mixedHold)) throw new Error('MIXED_HOLD must be hot or distributed');
const totalUsers = participants + holdParticipants;
const duration = __ENV.DURATION || '2m';
const entry = fixedEntry || __ENV.POLL_ENTRY || 'spread';
if (!['spread', 'simultaneous'].includes(entry)) throw new Error('POLL_ENTRY must be spread or simultaneous');
const runId = __ENV.RUN_ID;
if (!runId || !/^[a-zA-Z0-9_-]{1,40}$/.test(runId)) throw new Error('RUN_ID is required (1-40 letters, numbers, _ or -)');
if (mode === 'poll' && vus !== participants) throw new Error('poll requires VUS=PARTICIPANTS');
const users = new SharedArray('users', () => {
  const lines = open(__ENV.USERS_CSV || '../../seed/out/users.csv').trim().split('\n');
  const header = lines.shift().trim().split(',');
  const id = header.indexOf('user_id'), token = header.indexOf('access_token');
  if (id < 0 || token < 0) throw new Error('users.csv needs user_id and access_token');
  return lines.slice(0, totalUsers).map(line => { const c = line.trim().split(','); return { id: c[id], token: c[token] }; });
});
if (users.length < totalUsers || users.some(u => !u.id || !u.token) || new Set(users.map(u => u.id)).size !== totalUsers || new Set(users.map(u => u.token)).size !== totalUsers)
  throw new Error('users.csv needs enough distinct user IDs and access tokens');
const options = {
  scenarios: { ticketing: mode === 'poll' || mode === 'mixed'
    ? { executor: 'constant-vus', vus: mode === 'mixed' ? totalUsers : vus, duration }
    : { executor: 'shared-iterations', vus, iterations: participants, maxDuration: __ENV.MAX_DURATION || '5m' } },
  thresholds: {
    ticket_unexpected: ['rate==0'],
    ...(mode !== 'poll' ? {
      ticket_hold_attempts: [`count==${mode === 'mixed' ? holdParticipants : participants}`],
      ticket_hold_completed: [`count==${mode === 'mixed' ? holdParticipants : participants}`],
      ...(mode === 'replay' ? { ticket_replay_completed: [`count==${participants}`] } : {}),
      'ticket_outcomes{outcome:created}': [],
      'ticket_outcomes{outcome:unavailable}': [],
      'ticket_outcomes{outcome:lock_timeout}': [],
      'ticket_outcomes{outcome:unauthorized}': [],
      'ticket_outcomes{outcome:transport_error}': [],
      'ticket_outcomes{outcome:server_error}': [],
      'ticket_outcomes{outcome:unexpected_400}': [],
      'ticket_outcomes{outcome:unexpected_403}': [],
      'ticket_outcomes{outcome:unexpected_404}': [],
      'ticket_outcomes{outcome:unexpected_409}': [],
      'ticket_outcomes{outcome:replay_ok}': [],
      'ticket_outcomes{outcome:replay_error}': [],
      'ticket_hold_duration{outcome:created}': [],
      'ticket_hold_duration{outcome:unavailable}': [],
      'ticket_hold_duration{outcome:lock_timeout}': [],
    } : {}),
    ...(mode === 'mixed' ? { ticket_poll_requests: ['count>0'], ticket_poll_errors: ['rate==0'], ticket_hold_errors: ['rate==0'] } : {}),
    'ticket_poll_duration{outcome:ok}': [],
    'ticket_poll_duration{phase:entry}': [],
    'ticket_poll_duration{phase:poll}': [],
  },
  summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};
http.setResponseCallback(http.expectedStatuses(200, 201, 409));
const outcomes = new Counter('ticket_outcomes');
const unexpected = new Rate('ticket_unexpected');
const pollErrors = new Rate('ticket_poll_errors');
const holdErrors = new Rate('ticket_hold_errors');
const pollRequests = new Counter('ticket_poll_requests');
const holdAttempts = new Counter('ticket_hold_attempts');
const holdCompleted = new Counter('ticket_hold_completed');
const replayCompleted = new Counter('ticket_replay_completed');
const pollLatency = new Trend('ticket_poll_duration', true);
const holdLatency = new Trend('ticket_hold_duration', true);
function count(outcome, bad) { outcomes.add(1, { outcome }); unexpected.add(bad); }
function headers(user, key) { return { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) }; }
function setup() {
  const metrics = http.get(`${management}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management probe failed: ${metrics.status}`);
  const response = http.get(`${base}/api/performances/${performanceId}/seats`, { headers: headers(users[0]), tags: { name: 'setup' } });
  if (response.status !== 200) fail(`seat probe failed: ${response.status}`);
  let seats; try { seats = response.json(); } catch (_) { fail('seat probe did not return JSON'); }
  if (!Array.isArray(seats)) fail('seat probe did not return an array');
  const ids = seats.filter(s => s.status === 'AVAILABLE').map(s => s.seatId);
  if (!seats.length) fail('performance has no seats');
  if (mode !== 'poll' && !ids.length) fail('no available seats');
  if ((mode === 'distributed' || mode === 'replay') && ids.length < participants) fail('not enough available seats');
  if (mode === 'mixed' && mixedHold === 'distributed' && ids.length < holdParticipants) fail('not enough available seats for mixed distributed holds');
  if ((mode === 'hot' || (mode === 'mixed' && mixedHold === 'hot')) && ids.length < hotSeats) fail('not enough hot seats');
  if (mode === 'overlap' && (ids.length < 3 || participants < 2)) fail('overlap needs at least 3 seats and 2 users');
  console.log(`mode=${mode} entry=${mode === 'poll' ? entry : 'n/a'} performance=${performanceId} seats=${seats.length} available=${ids.length} pollUsers=${participants} holdUsers=${holdParticipants} mixedHold=${mode === 'mixed' ? mixedHold : 'n/a'} run=${runId}`);
  if (mode !== 'poll') { holdAttempts.add(0); holdCompleted.add(0); }
  if (mode === 'replay') replayCompleted.add(0);
  if (mode === 'mixed') pollRequests.add(0);
  return { ids, seatCount: seats.length };
}
function run(data) {
  const pollUser = mode === 'poll' || (mode === 'mixed' && exec.vu.idInTest <= participants);
  if (pollUser) {
    return runPoll(data, { base, performanceId, users, entry, vus, participants, mode, headers, pollRequests, pollErrors, count, pollLatency });
  }
  return runHold(data, { base, performanceId, users, mode, participants, holdDelay, mixedHold, hotSeats, runId, headers, holdAttempts, holdCompleted, replayCompleted, holdErrors, count, holdLatency });
}
function handleSummary(data) {
  const report = { ...data, conditions: { mode, entry: mode === 'poll' || mode === 'mixed' ? entry : null,
    mixedHold: mode === 'mixed' ? mixedHold : null, performanceId, participants, holdParticipants,
    vus: mode === 'mixed' ? totalUsers : vus, duration: mode === 'poll' || mode === 'mixed' ? duration : null,
    maxDuration: mode !== 'poll' && mode !== 'mixed' ? (__ENV.MAX_DURATION || '5m') : null,
    hotSeats, holdDelaySeconds: mode === 'mixed' ? holdDelay : null, runId, baseUrl: base } };
  const out = { stdout: JSON.stringify(report, null, 2) };
  if (__ENV.SUMMARY_PATH) out[__ENV.SUMMARY_PATH] = JSON.stringify(report, null, 2);
  return out;
}

return { options, setup, run, handleSummary };
}
