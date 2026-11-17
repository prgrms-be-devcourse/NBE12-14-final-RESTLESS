// 팬미팅 FM-EXP-05: 추첨 확정 직후 결과 조회 폭주. 사용법은 perf/scenarios/fanmeeting.md.
//
// fanmeeting-apply.js로 응모한 같은 회원들이 결과 발표 직후 계속 새로고침하는 상황을 만든다. 읽기만 한다.
//   1) GET /api/fan-meetings/{eventId}/result/me
//   2) 결과에 orderId가 있으면(당첨, 승격된 예비) GET /api/fan-meetings/{eventId}/orders/me
// 반복 n은 참여자 n % PARTICIPANTS의 요청이다. 처음 PARTICIPANTS번(첫 바퀴)은 회원마다 정확히 한 번이라
// 결과 분포(당첨·예비·미선발 수)를 세고 fanmeeting-check.sh의 DB 숫자와 비교한다. 그 뒤 반복은 새로고침이다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const SEED_OUT = __ENV.SEED_OUT || '../seed/out';
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://localhost:8081').replace(/\/$/, '');
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';

// fanmeeting-apply.js와 같은 호스트 규칙: perf-로 시작하고 허용 목록에 있는 호스트만, prod는 거부.
const allowedHosts = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map((h) => h.trim()).filter(Boolean);
for (const url of [BASE_URL, MANAGEMENT_URL]) {
  const host = url.replace(/^https?:\/\//, '').split(/[:/]/)[0];
  if (!/^perf-/i.test(host)) throw new Error(`host ${host} must start with perf- (e.g. perf-app)`);
  if (!allowedHosts.includes(host)) throw new Error(`host ${host} is not in PERF_ALLOWED_APP_HOSTS`);
  if (/prod/i.test(host)) throw new Error(`host ${host} looks like production`);
}

const eventFile = {};
if (__ENV.EVENT_FILE) {
  for (const line of open(__ENV.EVENT_FILE).split('\n')) {
    const m = line.match(/^\s*([A-Z_]+)=(.*)$/);
    if (m) eventFile[m[1]] = m[2].trim();
  }
}
const EVENT_ID = Number(__ENV.EVENT_ID || eventFile.FM_EVENT_ID);
if (!EVENT_ID) throw new Error('EVENT_FILE (from fanmeeting-prepare.sh) or EVENT_ID is required');

// 추첨 확정 전에 돌리면 모두 PENDING이라 발표 직후 상황이 아니다. 기본은 확정 뒤 상태만 허용한다.
// REQUIRE_DRAWN=false면 확정 전에도 돌린다(마감·추첨 중 새로고침 측정용, 이때 PENDING은 정상).
const REQUIRE_DRAWN = (__ENV.REQUIRE_DRAWN || 'true') !== 'false';
const DRAWN_STATUSES = ['PAYMENT_IN_PROGRESS', 'RECRUITMENT_COMPLETED'];

// 참여자 = apply와 같은 USER_OFFSET·PARTICIPANTS. 응모한 회원과 같게 맞춰야 NOT_APPLIED가 0이다.
const PARTICIPANTS = Number(__ENV.PARTICIPANTS || 1000);
const USER_OFFSET = Number(__ENV.USER_OFFSET || 0);
const users = new SharedArray('users', () => {
  const lines = open(`${SEED_OUT}/users.csv`).split('\n');
  const header = lines[0].split(',');
  const idx = { id: header.indexOf('user_id'), role: header.indexOf('role'), token: header.indexOf('access_token') };
  const rows = [];
  let seen = 0;
  for (let i = 1; i < lines.length && rows.length < PARTICIPANTS; i++) {
    if (!lines[i]) continue;
    const cols = lines[i].split(',');
    if (cols[idx.role] !== 'USER') continue;
    if (seen++ < USER_OFFSET) continue;
    rows.push({ id: cols[idx.id], token: cols[idx.token] });
  }
  return rows;
});
if (users.length < PARTICIPANTS) {
  throw new Error(`users.csv has ${users.length} USER rows after offset ${USER_OFFSET}, need ${PARTICIPANTS}`);
}

// 부하 모델: ramping-arrival-rate. 반복 1번 = 회원 1명의 새로고침 1번(요청 1~2개).
// 첫 바퀴를 다 돌려면 총 반복 수(대략 PEAK_RATE × (RAMP/2 + HOLD))가 PARTICIPANTS 이상이어야 한다.
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const RAMP = __ENV.RAMP || '5s';
const HOLD = __ENV.HOLD || '60s';
const PRE_VUS = Number(__ENV.PRE_VUS || 300);
const MAX_VUS = Number(__ENV.MAX_VUS || 2000);

const drawnThresholds = REQUIRE_DRAWN ? { fm_result_first_pending: ['count==0'] } : {};

export const options = {
  scenarios: {
    fanmeeting_result: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: PEAK_RATE, duration: RAMP },
        { target: PEAK_RATE, duration: HOLD },
        { target: 0, duration: '5s' },
      ],
    },
  },
  // 지연 목표는 미측정이라 임계값을 두지 않는다. 아래는 정합성과 전제 조건 검사다.
  thresholds: {
    fm_result_first_not_applied: ['count==0'],
    fm_order_not_found: ['count==0'],
    fm_order_mismatch: ['count==0'],
    fm_result_unknown: ['count==0'],
    fm_unauthorized: [{ threshold: 'count==0', abortOnFail: true }],
    fm_not_found: [{ threshold: 'count==0', abortOnFail: true }],
    ...drawnThresholds,
    'fm_read_duration{endpoint:result}': [],
    'fm_read_duration{endpoint:order}': [],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(200));

const RESULTS = ['WINNER', 'RESERVE', 'NOT_SELECTED', 'PENDING', 'NOT_APPLIED'];
// 첫 바퀴(회원당 1번)의 결과 분포. DB와 비교하는 숫자다.
const firstBy = Object.fromEntries(RESULTS.map((r) => [r, new Counter(`fm_result_first_${r.toLowerCase()}`)]));
const firstWithOrder = new Counter('fm_result_first_with_order');
const resultOk = new Counter('fm_result_ok');
const orderOk = new Counter('fm_order_ok');
const orderNotFound = new Counter('fm_order_not_found');
const orderMismatch = new Counter('fm_order_mismatch');
const resultUnknown = new Counter('fm_result_unknown');
const notFound = new Counter('fm_not_found');
const unauthorized = new Counter('fm_unauthorized');
const otherClientError = new Counter('fm_other_client_error');
const serverError = new Counter('fm_server_error');
const transportError = new Counter('fm_transport_error');
// k6가 잰 지연. 집 네트워크 왕복이 섞인 체감값이다. 서버 지연은 관리 포트의 http.server.requests로 본다.
const duration = new Trend('fm_read_duration', true);

export function setup() {
  const metrics = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${metrics.status}`);
  const detail = http.get(`${BASE_URL}/api/fan-meetings/${EVENT_ID}`, { tags: { name: 'setup' } });
  if (detail.status !== 200) fail(`GET /api/fan-meetings/${EVENT_ID} returned ${detail.status}`);
  const status = detail.json('status');
  if (REQUIRE_DRAWN && !DRAWN_STATUSES.includes(status)) {
    fail(`event ${EVENT_ID} status is ${status}, need ${DRAWN_STATUSES} (draw not finalized yet). `
      + 'Wait for the draw (fanmeeting-check.sh shows DrawRun) or set REQUIRE_DRAWN=false.');
  }
  console.log(`event=${EVENT_ID} status=${status} participants=${PARTICIPANTS} offset=${USER_OFFSET} `
    + `peak=${PEAK_RATE}/s ramp=${RAMP} hold=${HOLD}`);
  return { status };
}

export default function () {
  const n = exec.scenario.iterationInTest;
  const firstPass = n < users.length;
  const user = users[n % users.length];
  const headers = { Authorization: `Bearer ${user.token}` };

  const res = http.get(`${BASE_URL}/api/fan-meetings/${EVENT_ID}/result/me`, {
    headers, tags: { name: 'GET /api/fan-meetings/{eventId}/result/me', endpoint: 'result' },
  });
  duration.add(res.timings.duration, { endpoint: 'result' });
  if (!countError(res)) return;
  resultOk.add(1);

  let body;
  try { body = res.json(); } catch (e) { body = null; }
  const result = body && body.result;
  if (!RESULTS.includes(result)) {
    resultUnknown.add(1);
    return;
  }
  if (firstPass) firstBy[result].add(1);
  check(res, { 'result is not NOT_APPLIED': () => result !== 'NOT_APPLIED' });

  if (!body.orderId) return;
  if (firstPass) firstWithOrder.add(1);
  const order = http.get(`${BASE_URL}/api/fan-meetings/${EVENT_ID}/orders/me`, {
    headers, tags: { name: 'GET /api/fan-meetings/{eventId}/orders/me', endpoint: 'order' },
  });
  duration.add(order.timings.duration, { endpoint: 'order' });
  if (order.status === 404 && errorCode(order) === 'ORDER_NOT_FOUND') {
    // 결과에는 주문 번호가 있는데 주문 조회가 없다고 하면 두 조회가 서로 다른 상태를 보고 있다.
    orderNotFound.add(1);
    return;
  }
  if (!countError(order)) return;
  orderOk.add(1);
  let orderId = null;
  try { orderId = order.json('orderId'); } catch (e) { /* 아래에서 불일치로 센다 */ }
  if (orderId !== body.orderId) orderMismatch.add(1);
}

// 200이면 true. 아니면 종류별로 세고 false.
function countError(res) {
  if (res.status === 200) return true;
  if (res.status === 0) transportError.add(1);
  else if (res.status === 401) unauthorized.add(1);
  else if (res.status >= 500) serverError.add(1);
  else if (errorCode(res) === 'FAN_MEETING_NOT_FOUND') notFound.add(1);
  else otherClientError.add(1);
  return false;
}

function errorCode(res) {
  try { return res.json('code'); } catch (e) { return ''; }
}

const FIRST_METRICS = [...RESULTS.map((r) => `fm_result_first_${r.toLowerCase()}`), 'fm_result_first_with_order'];
const OUTCOME_METRICS = [
  'fm_result_ok', 'fm_order_ok', 'fm_order_not_found', 'fm_order_mismatch', 'fm_result_unknown', 'fm_not_found',
  'fm_unauthorized', 'fm_other_client_error', 'fm_server_error', 'fm_transport_error', 'dropped_iterations',
];

export function handleSummary(data) {
  const value = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const trendOf = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const fmt = (v) => (v === undefined ? '-' : `${v.toFixed(1)}ms`);
  const latency = (t) => `med ${fmt(t.med)}  p95 ${fmt(t['p(95)'])}  p99 ${fmt(t['p(99)'])}  max ${fmt(t.max)}`;
  const firstTotal = RESULTS.reduce((sum, r) => sum + value(`fm_result_first_${r.toLowerCase()}`), 0);
  const iterations = value('iterations');
  const reqRate = data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0;
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  // 첫 바퀴가 다 돌지 않았으면 분포를 DB와 비교할 수 없다. 반복 수를 늘린다(HOLD 또는 PEAK_RATE).
  const firstPassDone = iterations >= PARTICIPANTS;
  if (!firstPassDone) failedThresholds.push(`first pass incomplete (${iterations}/${PARTICIPANTS} iterations)`);
  const status = data.setup_data ? data.setup_data.status : null;
  const lines = [
    '',
    `fanmeeting event ${EVENT_ID} (status ${status}), result read storm, participants ${PARTICIPANTS} `
      + `(offset ${USER_OFFSET}), peak ${PEAK_RATE}/s (ramp ${RAMP}, hold ${HOLD})`,
    `  iterations ${iterations}, http_reqs ${value('http_reqs')} (${reqRate.toFixed(1)}/s)`,
    `  first pass (one per member, compare with fanmeeting-check.sh) ${firstPassDone ? '' : '— INCOMPLETE'}`,
    ...FIRST_METRICS.map((m) => `    ${m.padEnd(28)} ${value(m)}`),
    `    total                        ${firstTotal} (must equal participants ${PARTICIPANTS})`,
    ...OUTCOME_METRICS.map((m) => `  ${m.padEnd(30)} ${value(m)}`),
    '  k6 latency, client-observed (체감값, 서버 지연은 8081 http.server.requests)',
    `    result/me      ${latency(trendOf('fm_read_duration{endpoint:result}'))}`,
    `    orders/me      ${latency(trendOf('fm_read_duration{endpoint:order}'))}`,
    `  load generator  http_req_connecting p95 ${fmt(trendOf('http_req_connecting')['p(95)'])}`
      + `, http_req_blocked p95 ${fmt(trendOf('http_req_blocked')['p(95)'])}`,
    `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`,
    '',
  ];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify({
      conditions: { eventId: EVENT_ID, eventStatus: status, requireDrawn: REQUIRE_DRAWN, participants: PARTICIPANTS,
        userOffset: USER_OFFSET, peakRate: PEAK_RATE, ramp: RAMP, hold: HOLD, preVUs: PRE_VUS, maxVUs: MAX_VUS,
        baseUrl: BASE_URL },
      firstPass: { complete: firstPassDone, total: firstTotal,
        ...Object.fromEntries(FIRST_METRICS.map((m) => [m, value(m)])) },
      outcomes: Object.fromEntries(OUTCOME_METRICS.map((m) => [m, value(m)])),
      iterations,
      httpReqs: value('http_reqs'),
      clientLatencyNote: 'k6 지연은 집 네트워크 왕복이 섞인 체감값이다. 서버 지연은 관리 포트 http.server.requests로 본다.',
      loadGenerator: {
        dropped_iterations: value('dropped_iterations'),
        http_req_connecting: trendOf('http_req_connecting'),
        http_req_blocked: trendOf('http_req_blocked'),
      },
      failedThresholds,
      metrics: data.metrics,
    }, null, 2);
  }
  return out;
}
