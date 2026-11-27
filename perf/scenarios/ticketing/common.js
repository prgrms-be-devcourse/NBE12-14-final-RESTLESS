// 티켓팅 시나리오 공통 실행 로직. 시나리오 파일에서 종류를 지정한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { fail, sleep } from 'k6';
import { runPoll } from './poll-logic.js';
import { runHold } from './hold-logic.js';
import { runCheckout } from './checkout-logic.js';
import { SharedArray } from 'k6/data';
import { Counter, Trend, Rate } from 'k6/metrics';

export function createRuntime(mode, fixedEntry = null, fixedMixedHold = null) {
const base = (__ENV.BASE_URL || '').replace(/\/$/, '');
const management = (__ENV.MANAGEMENT_URL || '').replace(/\/$/, '');
for (const value of [base, ...(management ? [management] : [])]) {
  if (!/^https?:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(value))
    throw new Error('BASE_URL and MANAGEMENT_URL must be HTTP or HTTPS origins');
}
function positive(name, fallback) {
  const n = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name} must be a positive integer`);
  return n;
}
if (!['poll', 'page', 'distributed', 'hot', 'overlap', 'replay', 'mixed', 'checkout'].includes(mode)) throw new Error('invalid MODE');
const performanceId = positive('PERFORMANCE_ID', 0);
const participants = positive('PARTICIPANTS', 100);
const vus = positive('VUS', mode === 'poll' ? participants : 50);
const hotSeats = positive('HOT_SEATS', 1);
const holdParticipants = mode === 'mixed' ? positive('HOLD_PARTICIPANTS', 20) : 0;
// 0이면 바로 출발, 양수면 준비가 끝난 뒤 N초 뒤의 같은 시각에 함께 출발한다(대규모 동시 출발용). 조회·화면 진입·혼합에 적용한다.
const startDelay = Number(__ENV.START_DELAY_SECONDS || 0);
// checkout에서 선점 전에 좌석 화면 진입(좌석·구매 가능 정보)을 함께 보낼지. 1이면 보낸다.
const checkoutPageEntry = (__ENV.CHECKOUT_PAGE_ENTRY || '0') === '1';
// 도착률 모드: ARRIVAL_RATE(초당 사용자)로 ARRIVAL_SECONDS 동안 고르게 들어온다. 반복 하나가 사용자 한 명이다.
// 순간 몰림이 아니라 계속 들어오는 부하에서 처리 한계를 본다. simultaneous와 page에만 쓴다.
const arrivalRate = Number(__ENV.ARRIVAL_RATE || 0);
const arrivalSeconds = Number(__ENV.ARRIVAL_SECONDS || 0);
const arrival = arrivalRate > 0;
if (!Number.isSafeInteger(arrivalRate) || arrivalRate < 0 || arrivalRate > 5000) throw new Error('ARRIVAL_RATE must be an integer from 0 to 5000');
if (arrival && (!Number.isSafeInteger(arrivalSeconds) || arrivalSeconds < 1 || arrivalSeconds > 3600)) throw new Error('ARRIVAL_SECONDS must be an integer from 1 to 3600');
if (arrival && !['poll', 'page'].includes(mode)) throw new Error('ARRIVAL_RATE is supported for simultaneous and page');
if (arrival && participants !== arrivalRate * arrivalSeconds) throw new Error('arrival mode requires USERS = ARRIVAL_RATE x ARRIVAL_SECONDS');
if (!Number.isSafeInteger(startDelay) || startDelay < 0 || startDelay > 600) throw new Error('START_DELAY_SECONDS must be an integer from 0 to 600');
const mixedHold = fixedMixedHold || __ENV.MIXED_HOLD || 'hot';
if (mode === 'mixed' && !['hot', 'distributed'].includes(mixedHold)) throw new Error('MIXED_HOLD must be hot or distributed');
const totalUsers = participants + holdParticipants;
const rowOffset = Number(__ENV.ROW_OFFSET || 0);
if (!Number.isSafeInteger(rowOffset) || rowOffset < 0) throw new Error('ROW_OFFSET must be a nonnegative integer');
const setupMode = __ENV.SETUP || 'csv';
const seatEncoding = __ENV.SEAT_ENCODING || 'identity';
if (!['identity', 'gzip'].includes(seatEncoding)) throw new Error('SEAT_ENCODING must be identity or gzip');
if (!['csv', 'self'].includes(setupMode)) throw new Error('SETUP must be csv or self');
if (setupMode === 'self' && totalUsers > 1000) throw new Error('self setup supports at most 1000 seeded users');
const refreshCount = Number(__ENV.REFRESH_COUNT || 0);
if (!Number.isSafeInteger(refreshCount) || refreshCount < 0 || refreshCount > 100) throw new Error('REFRESH_COUNT must be an integer from 0 to 100');
const refreshInterval = positive('REFRESH_INTERVAL_SECONDS', 30);
// 진입 방식은 화면 진입(page, mixed 조회자)에만 적용한다. 좌석 조회(poll)와 선점 시나리오는 항상 동시 출발한다.
const entry = mode === 'page' || mode === 'mixed' ? (fixedEntry || __ENV.POLL_ENTRY || 'spread') : 'simultaneous';
if (!['spread', 'simultaneous'].includes(entry)) throw new Error('POLL_ENTRY must be spread or simultaneous');
if (startDelay && !['poll', 'page', 'mixed'].includes(mode)) throw new Error('START_DELAY_SECONDS is supported for simultaneous, page and mixed');
if (arrival && startDelay) throw new Error('ARRIVAL_RATE cannot be combined with START_DELAY_SECONDS');
const runId = __ENV.RUN_ID;
if (!runId || !/^[a-zA-Z0-9_-]{1,40}$/.test(runId)) throw new Error('RUN_ID is required (1-40 letters, numbers, _ or -)');
if (!arrival && (mode === 'poll' || mode === 'page') && vus !== participants) throw new Error('poll and page require VUS=PARTICIPANTS');
const users = setupMode === 'csv' ? new SharedArray('users', () => {
  const lines = open(__ENV.USERS_CSV || '../../seed/out/users.csv').trim().split('\n');
  const header = lines.shift().trim().split(',');
  const id = header.indexOf('user_id'), token = header.indexOf('access_token');
  if (id < 0 || token < 0) throw new Error('users.csv needs user_id and access_token');
  return lines.slice(rowOffset, rowOffset + totalUsers).map(line => { const c = line.trim().split(','); return { id: c[id], token: c[token] }; });
}) : [];
if (setupMode === 'csv' && (users.length < totalUsers || users.some(u => !u.id || !u.token) || new Set(users.map(u => u.id)).size !== totalUsers || new Set(users.map(u => u.token)).size !== totalUsers))
  throw new Error('users.csv needs enough distinct user IDs and access tokens');
const options = {
  setupTimeout: '10m',
  scenarios: { ticketing: arrival
    ? { executor: 'constant-arrival-rate', rate: arrivalRate, timeUnit: '1s', duration: `${arrivalSeconds}s`,
        // 한 사용자가 끝나기 전에 다음 사용자가 오므로 VU를 넉넉히 둔다. 모자라면 dropped_iterations가 늘어 실패한다.
        preAllocatedVUs: positive('ARRIVAL_PRE_VUS', Math.min(arrivalRate * 5, 3000)),
        maxVUs: positive('ARRIVAL_MAX_VUS', Math.min(arrivalRate * 20, 6000)),
        gracefulStop: __ENV.GRACEFUL_STOP || '30s' }
    : ['poll', 'page', 'mixed'].includes(mode)
    ? { executor: 'per-vu-iterations', vus: mode === 'mixed' ? totalUsers : vus, iterations: 1, maxDuration: __ENV.MAX_DURATION || '5m', gracefulStop: __ENV.GRACEFUL_STOP || '30s' }
    : { executor: 'shared-iterations', vus, iterations: participants, maxDuration: __ENV.MAX_DURATION || '5m', gracefulStop: __ENV.GRACEFUL_STOP || '30s' } },
  thresholds: {
    ticket_unexpected: ['rate==0'],
    ...(['poll', 'page', 'mixed'].includes(mode) ? { ticket_seat_encoding_errors: ['rate==0'] } : {}),
    ...(['page', 'mixed'].includes(mode) ? { ticket_availability_requests: ['count>0'], ticket_availability_errors: ['rate==0'] } : {}),
    ...(mode !== 'poll' && mode !== 'page' ? {
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
      ...(mode === 'replay' ? { 'ticket_hold_duration{outcome:replay_ok}': [] } : {}),
      ...(mode === 'checkout' ? {
        // 중간에 멈추거나 시간이 초과돼도 통과하지 않게 단계별 완료 수를 참가자 수와 맞춘다.
        ticket_checkout_completed: [`count==${participants}`],
        ticket_payment_prepare_completed: [`count==${participants}`],
        'ticket_outcomes{outcome:prepared}': [`count==${participants}`],
        // 화면 진입 실패는 ticket_unexpected로 실행을 실패시킨다.
        ...(checkoutPageEntry ? { 'ticket_page_entry_duration{outcome:ok}': [], 'ticket_page_entry_duration{outcome:error}': [] } : {}),
        'ticket_checkout_duration{outcome:ok}': [], 'ticket_checkout_duration{outcome:lock_timeout}': [], 'ticket_checkout_duration{outcome:error}': [],
        'ticket_payment_prepare_duration{outcome:ok}': [], 'ticket_payment_prepare_duration{outcome:error}': [],
        'ticket_journey_duration{outcome:ok}': [], 'ticket_journey_duration{outcome:error}': [],
      } : {}),
    } : {}),
    ...(mode === 'mixed' ? { ticket_poll_requests: [`count>=${participants}`], ticket_poll_errors: ['rate==0'], ticket_hold_errors: ['rate==0'] } : {}),
    ...(['poll', 'page'].includes(mode) ? { ticket_poll_requests: [`count>=${participants}`], ticket_poll_errors: ['rate==0'] } : {}),
    // 부하 발생기가 목표 유입을 만들었는지: 못 보낸 반복이 없어야 한다.
    ...(arrival ? { dropped_iterations: ['count==0'], 'ticket_arrival_lag': [] } : {}),
    ...(refreshCount && ['poll', 'page', 'mixed'].includes(mode) ? { ticket_refresh_requests: [`count==${participants * refreshCount}`] } : {}),
    'ticket_poll_duration{outcome:ok}': [],
    'ticket_poll_duration{phase:entry}': [],
    ...(['page', 'mixed'].includes(mode) ? { 'ticket_page_entry_duration{outcome:ok}': [], 'ticket_page_entry_duration{outcome:error}': [] } : {}),
    'ticket_start_offset{phase:entry}': [],
    ...(mode !== 'poll' && mode !== 'page' ? { 'ticket_start_offset{phase:hold}': [] } : {}),
  },
  summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};
http.setResponseCallback(http.expectedStatuses(200, 201, 409));
const outcomes = new Counter('ticket_outcomes');
const unexpected = new Rate('ticket_unexpected');
const pollErrors = new Rate('ticket_poll_errors');
const holdErrors = new Rate('ticket_hold_errors');
const pollRequests = new Counter('ticket_poll_requests');
const refreshRequests = new Counter('ticket_refresh_requests');
const holdAttempts = new Counter('ticket_hold_attempts');
const holdCompleted = new Counter('ticket_hold_completed');
const replayCompleted = new Counter('ticket_replay_completed');
const pollLatency = new Trend('ticket_poll_duration', true);
// 시나리오 시작부터 사용자의 첫 요청 직전까지. 동시 출발이 실제로 지켜졌는지 확인한다.
const startOffset = new Trend('ticket_start_offset', true);
// 도착률 모드에서 예정 시각보다 늦게 출발한 시간. 크면 부하 발생기가 도착률을 만들지 못한 것이다.
const arrivalLag = new Trend('ticket_arrival_lag', true);
// 시나리오 시작(또는 지정 출발 시각)부터 조회자의 화면 진입 응답이 끝날 때까지. mixed에서 선점 출발과 겹쳤는지 본다.
const entryEndOffset = new Trend('ticket_entry_end_offset', true);
const seatGzipResponses = new Counter('ticket_seat_gzip_responses');
const seatEncodingErrors = new Rate('ticket_seat_encoding_errors');
const availabilityRequests = new Counter('ticket_availability_requests');
const availabilityErrors = new Rate('ticket_availability_errors');
const availabilityLatency = new Trend('ticket_availability_duration', true);
const pageEntryLatency = new Trend('ticket_page_entry_duration', true);
const holdLatency = new Trend('ticket_hold_duration', true);
const checkoutLatency = new Trend('ticket_checkout_duration', true);
const prepareLatency = new Trend('ticket_payment_prepare_duration', true);
// checkout: 선점 요청 시작부터 결제 준비 응답까지(화면 진입 제외).
const journeyLatency = new Trend('ticket_journey_duration', true);
const checkoutCompleted = new Counter('ticket_checkout_completed');
const prepareCompleted = new Counter('ticket_payment_prepare_completed');
function count(outcome, bad) { outcomes.add(1, { outcome }); unexpected.add(bad); }
function headers(user, key) { return { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) }; }
function seatHeaders(user) { return { ...headers(user), 'Accept-Encoding': seatEncoding }; }
function encodingMatches(res) {
  const actual = (res.headers['Content-Encoding'] || '').toLowerCase();
  return seatEncoding === 'gzip' ? actual === 'gzip' : actual === '' || actual === 'identity';
}
function setup() {
  if (management) {
    const metrics = http.get(`${management}/actuator/prometheus`, { tags: { name: 'setup' } });
    if (metrics.status !== 200) fail(`management probe failed: ${metrics.status}`);
  }
  const activeUsers = [];
  if (setupMode === 'self') {
    for (let id = 1; id <= totalUsers; id++) {
      const login = http.post(`${base}/api/auth/login`, JSON.stringify({ email: `fanq${id}@fanq.com`, password: __ENV.SEED_PASSWORD || '0000' }), {
        headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup' }, redirects: 0,
      });
      let token; try { token = login.json('accessToken'); } catch (_) { /* invalid response */ }
      if (login.status !== 200 || typeof token !== 'string' || !token) fail(`seed login failed for account ${id}: HTTP ${login.status}`);
      activeUsers.push({ id: String(id), token });
      if (id < totalUsers) sleep(0.1);
    }
    if (new Set(activeUsers.map(u => u.token)).size !== totalUsers) fail('seed logins returned duplicate tokens');
  } else {
    for (const user of users) activeUsers.push(user);
  }
  const response = http.get(`${base}/api/performances/${performanceId}/seats`, { headers: seatHeaders(activeUsers[0]), tags: { name: 'setup' } });
  if (response.status !== 200) fail(`seat probe failed: ${response.status}`);
  if (!encodingMatches(response)) fail(`seat probe encoding mismatch: requested ${seatEncoding}, received ${response.headers['Content-Encoding'] || 'identity'}`);
  let seats; try { seats = response.json(); } catch (_) { fail('seat probe did not return JSON'); }
  if (!Array.isArray(seats)) fail('seat probe did not return an array');
  const ids = seats.filter(s => s.status === 'AVAILABLE').map(s => s.seatId);
  if (!seats.length) fail('performance has no seats');
  if (mode !== 'poll' && mode !== 'page' && !ids.length) fail('no available seats');
  if ((mode === 'distributed' || mode === 'replay' || mode === 'checkout') && ids.length < participants) fail('not enough available seats');
  if (mode === 'mixed' && mixedHold === 'distributed' && ids.length < holdParticipants) fail('not enough available seats for mixed distributed holds');
  if ((mode === 'hot' || (mode === 'mixed' && mixedHold === 'hot')) && ids.length < hotSeats) fail('not enough hot seats');
  if (mode === 'overlap' && (ids.length < 3 || participants < 2)) fail('overlap needs at least 3 seats and 2 users');
  console.log(`mode=${mode} entry=${entry} performance=${performanceId} seats=${seats.length} available=${ids.length} pollUsers=${participants} holdUsers=${holdParticipants} mixedHold=${mode === 'mixed' ? mixedHold : 'n/a'} run=${runId}`);
  if (mode !== 'poll' && mode !== 'page') { holdAttempts.add(0); holdCompleted.add(0); }
  if (mode === 'replay') replayCompleted.add(0);
  if (mode === 'checkout') { checkoutCompleted.add(0); prepareCompleted.add(0); }
  if (mode === 'mixed') pollRequests.add(0);
  if (refreshCount && ['poll', 'page', 'mixed'].includes(mode)) refreshRequests.add(0);
  const startAt = startDelay ? Date.now() + startDelay * 1000 : null;
  if (startAt) console.log(`start scheduled=${new Date(startAt).toISOString()}`);
  return { ids, seatCount: seats.length, seatIds: seats.map(s => s.seatId), users: activeUsers, startAt };
}
function run(data) {
  const activeUsers = data.users;
  if (data.startAt) { const wait = data.startAt - Date.now(); if (wait > 0) sleep(wait / 1000); }
  const origin = data.startAt || exec.scenario.startTime;
  const pollUser = mode === 'poll' || mode === 'page' || (mode === 'mixed' && exec.vu.idInTest <= participants);
  if (pollUser) {
    return runPoll(data, { base, performanceId, users: activeUsers, entry, vus, participants, mode, headers, seatHeaders, encodingMatches, seatGzipResponses, seatEncodingErrors, pollRequests, pollErrors, count, pollLatency, availabilityRequests, availabilityErrors, availabilityLatency, pageEntryLatency, startOffset, entryEndOffset, origin, refreshCount, refreshInterval, refreshRequests, arrival, arrivalRate, arrivalLag });
  }
  if (mode === 'checkout') {
    return runCheckout(data, { base, performanceId, users: activeUsers, runId, origin, headers, seatHeaders, encodingMatches, count, startOffset,
      pageEntry: checkoutPageEntry, pageEntryLatency, holdAttempts, holdCompleted, holdLatency, checkoutLatency, prepareLatency, journeyLatency,
      checkoutCompleted, prepareCompleted });
  }
  return runHold(data, { base, performanceId, users: activeUsers, mode, participants, holdParticipants, entry, origin, mixedHold, hotSeats, runId, headers, holdAttempts, holdCompleted, replayCompleted, holdErrors, count, holdLatency, startOffset });
}
function handleSummary(data) {
  const { setup_data: setupData, ...safeData } = data;
  const safeSetup = setupData ? { ...setupData, ...(Array.isArray(setupData.users) ? { users: setupData.users.map(user => ({ id: user.id })) } : {}) } : setupData;
  const report = { ...safeData, setup_data: safeSetup, conditions: { mode, entry: arrival ? 'arrival' : entry, arrivalRate: arrival ? arrivalRate : null, arrivalSeconds: arrival ? arrivalSeconds : null,
    mixedHold: mode === 'mixed' ? mixedHold : null, performanceId, participants, holdParticipants,
    vus: mode === 'mixed' ? totalUsers : vus, refreshCount, refreshIntervalSeconds: refreshCount ? refreshInterval : null,
    maxDuration: __ENV.MAX_DURATION || '5m',
    hotSeats, startDelaySeconds: startDelay || null, checkoutPageEntry: mode === 'checkout' ? checkoutPageEntry : null, seatEncoding, runId, baseUrl: base, rowOffset } };
  const out = { stdout: JSON.stringify(report, null, 2) };
  if (__ENV.SUMMARY_PATH) out[__ENV.SUMMARY_PATH] = JSON.stringify(report, null, 2);
  return out;
}

return { options, setup, run, handleSummary };
}
