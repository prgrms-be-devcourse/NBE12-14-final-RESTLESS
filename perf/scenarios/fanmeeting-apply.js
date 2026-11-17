// 팬미팅 S1(C46): 응모 오픈 순간 서로 다른 회원의 첫 응모 몰림. 사용법은 perf/scenarios/fanmeeting.md.
//
// POST /api/fan-meetings/{eventId}/applications만 친다. 행사는 fanmeeting-prepare.sh가 만든다.
// 반복마다 users.csv의 다른 행을 쓴다. 한 계정은 테스트 전체에서 한 번만 요청하고 다시 보내지 않는다.
//   MODE=open  (기본) 시나리오 A·B. 기준 시각 = 오픈(entryOpensAt). START_OFFSET=-2s면 오픈 2초 전부터 보낸다.
//   MODE=close 시나리오 C. 기준 시각 = 마감(entryClosesAt). 기준 시각 1초 뒤부터 보낸 요청의 201은 0이어야 한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const SEED_OUT = __ENV.SEED_OUT || '../seed/out';
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://localhost:8081').replace(/\/$/, '');
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';
const MODE = __ENV.MODE || 'open';
if (MODE !== 'open' && MODE !== 'close') throw new Error(`MODE must be open or close: ${MODE}`);

// 배포 서버에 부하를 주지 않도록 perf로 시작하고 허용 목록에 정확히 있는 호스트만 친다 (drop-reservation-spike.js와 같은 규칙).
const allowedHosts = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map((h) => h.trim()).filter(Boolean);
for (const url of [BASE_URL, MANAGEMENT_URL]) {
  const host = url.replace(/^https?:\/\//, '').split(/[:/]/)[0];
  if (!/^perf-/i.test(host)) throw new Error(`host ${host} must start with perf- (e.g. perf-app)`);
  if (!allowedHosts.includes(host)) throw new Error(`host ${host} is not in PERF_ALLOWED_APP_HOSTS`);
  if (/prod/i.test(host)) throw new Error(`host ${host} looks like production`);
}

// fanmeeting-prepare.sh가 남긴 KEY=VALUE 파일. EVENT_ID 등 환경 변수가 있으면 그 값이 이긴다.
const eventFile = {};
if (__ENV.EVENT_FILE) {
  for (const line of open(__ENV.EVENT_FILE).split('\n')) {
    const m = line.match(/^\s*([A-Z_]+)=(.*)$/);
    if (m) eventFile[m[1]] = m[2].trim();
  }
}
const EVENT_ID = Number(__ENV.EVENT_ID || eventFile.FM_EVENT_ID);
const OPENS_AT = __ENV.ENTRY_OPENS_AT || eventFile.FM_ENTRY_OPENS_AT;
const CLOSES_AT = __ENV.ENTRY_CLOSES_AT || eventFile.FM_ENTRY_CLOSES_AT;
if (!EVENT_ID || !OPENS_AT || !CLOSES_AT) {
  throw new Error('EVENT_FILE (from fanmeeting-prepare.sh) or EVENT_ID, ENTRY_OPENS_AT, ENTRY_CLOSES_AT are required');
}
const OPENS_MS = Date.parse(OPENS_AT);
const CLOSES_MS = Date.parse(CLOSES_AT);

function parseDuration(text) {
  const m = String(text).trim().match(/^([+-]?\d+(?:\.\d+)?)(ms|s|m)?$/);
  if (!m) throw new Error(`bad duration: ${text} (e.g. -2s, 500ms, 1m)`);
  return Number(m[1]) * { ms: 1, s: 1000, m: 60000 }[m[2] || 's'];
}

// 기준 시각: MODE=open이면 오픈, close면 마감. 결과 구간(before, 0~1초, 1~2초, 2초 이후)도 이 시각에서 잰다.
const REF_MS = MODE === 'open' ? OPENS_MS : CLOSES_MS;
const START_OFFSET = __ENV.START_OFFSET || (MODE === 'open' ? '0s' : '-3s');
const START_MS = __ENV.START_AT ? Date.parse(__ENV.START_AT) : REF_MS + parseDuration(START_OFFSET);
if (Number.isNaN(START_MS)) throw new Error(`bad START_AT: ${__ENV.START_AT}`);
const WAIT_MS = START_MS - Date.now();
if (WAIT_MS > 30 * 60 * 1000) throw new Error(`START_AT is ${Math.round(WAIT_MS / 60000)} minutes away. Start k6 closer to it.`);

// 참여자 = users.csv에서 USER_OFFSET 다음부터 PARTICIPANTS행. 같은 행사에 다시 돌릴 때는 USER_OFFSET을 바꾼다.
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

// 부하 모델: 드롭과 같은 ramping-arrival-rate. 총 도착 수가 참여자보다 많으면 남는 반복은 skipped_no_account.
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const RAMP = __ENV.RAMP || '5s';
const HOLD = __ENV.HOLD || '20s';
const PRE_VUS = Number(__ENV.PRE_VUS || 500);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);

// MODE=close: 마감 1초 뒤부터 보낸 요청이 성공하면 안 된다. 0~1초는 노트북·서버 시계 차이와 전송 중인 요청 몫으로 둔다.
const closeThresholds = MODE === 'close'
  ? { fm_applied_1_2s: ['count==0'], fm_applied_after_2s: ['count==0'] }
  : {};

export const options = {
  // setup이 시작 시각까지 기다리므로 그만큼 늘린다.
  setupTimeout: `${Math.max(60, Math.ceil(WAIT_MS / 1000) + 60)}s`,
  scenarios: {
    fanmeeting_apply: {
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
  // 업무 거절(409)은 http_req_failed에 넣지 않는다. 503 BUSY, 401, 5xx, 연결 오류는 실패다.
  // 지연 목표 수치는 미측정이라 임계값을 두지 않는다. 아래는 정합성과 전제 조건 검사다.
  thresholds: {
    fm_applied: [`count<=${PARTICIPANTS}`],
    fm_already_applied: ['count==0'],
    fm_not_found: [{ threshold: 'count==0', abortOnFail: true }],
    fm_unauthorized: [{ threshold: 'count==0', abortOnFail: true }],
    ...closeThresholds,
    'fm_apply_duration{outcome:applied}': [],
    'fm_apply_duration{outcome:window_closed}': [],
    'fm_apply_duration{outcome:busy}': [],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(201, 409));

const applied = new Counter('fm_applied');
const alreadyApplied = new Counter('fm_already_applied');
const windowClosed = new Counter('fm_window_closed');
const busy = new Counter('fm_busy');
const notFound = new Counter('fm_not_found');
const unauthorized = new Counter('fm_unauthorized');
const otherClientError = new Counter('fm_other_client_error');
const serverError = new Counter('fm_server_error');
const transportError = new Counter('fm_transport_error');
const skipped = new Counter('skipped_no_account');
// 기준 시각(오픈 또는 마감)에서 요청을 보낸 시각까지 경과한 구간별 수. 노트북 시계 기준이다.
const BUCKETS = ['before', '0_1s', '1_2s', 'after_2s'];
const appliedBy = Object.fromEntries(BUCKETS.map((b) => [b, new Counter(`fm_applied_${b}`)]));
const closedBy = Object.fromEntries(BUCKETS.map((b) => [b, new Counter(`fm_window_closed_${b}`)]));
// k6가 잰 지연. 집 네트워크 왕복이 섞인 체감값이다. 서버 지연은 관리 포트의 http.server.requests로 본다.
const duration = new Trend('fm_apply_duration', true);

export function setup() {
  // perf 프로필만 관리 포트에 prometheus를 연다. 응답하지 않으면 perf 앱이 아니므로 멈춘다.
  const metrics = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${metrics.status}`);
  const detail = http.get(`${BASE_URL}/api/fan-meetings/${EVENT_ID}`, { tags: { name: 'setup' } });
  if (detail.status !== 200) fail(`GET /api/fan-meetings/${EVENT_ID} returned ${detail.status}`);
  const status = detail.json('status');
  const okStatus = MODE === 'open' ? ['SCHEDULED', 'OPEN'] : ['OPEN'];
  if (!okStatus.includes(status)) fail(`event ${EVENT_ID} status is ${status}, need ${okStatus}. Run fanmeeting-prepare.sh again.`);
  if (MODE === 'open' && Date.now() >= CLOSES_MS) fail(`entry window already closed at ${CLOSES_AT}`);

  // 노트북과 서버 시계 차이(초 단위 Date 헤더라 ±1초). 구간 집계를 해석할 때 같이 본다.
  const serverDate = Date.parse(detail.headers.Date || '');
  const clockOffsetMs = Number.isNaN(serverDate) ? null : serverDate - Date.now();

  const waitMs = START_MS - Date.now();
  console.log(`event=${EVENT_ID} mode=${MODE} opens=${OPENS_AT} closes=${CLOSES_AT} participants=${PARTICIPANTS} `
    + `offset=${USER_OFFSET} peak=${PEAK_RATE}/s start=${new Date(START_MS).toISOString()} wait=${Math.max(0, waitMs)}ms `
    + `serverClockOffset~${clockOffsetMs}ms`);
  if (waitMs > 0) sleep(waitMs / 1000);
  return { clockOffsetMs };
}

export default function () {
  const n = exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  const user = users[n];
  const sentAt = Date.now();
  const res = http.post(`${BASE_URL}/api/fan-meetings/${EVENT_ID}/applications`, null, {
    headers: { Authorization: `Bearer ${user.token}` },
    tags: { name: 'POST /api/fan-meetings/{eventId}/applications' },
  });
  const bucket = bucketOf(sentAt - REF_MS);
  const outcome = classify(res, bucket);
  duration.add(res.timings.duration, { outcome });
  check(res, { 'no server error': () => !['server_error', 'transport_error', 'busy'].includes(outcome) });
}

function bucketOf(elapsedMs) {
  if (elapsedMs < 0) return 'before';
  if (elapsedMs < 1000) return '0_1s';
  if (elapsedMs < 2000) return '1_2s';
  return 'after_2s';
}

function classify(res, bucket) {
  if (res.status === 0) return count(transportError, 'transport_error');
  if (res.status === 201) {
    appliedBy[bucket].add(1);
    return count(applied, 'applied');
  }
  if (res.status === 401) return count(unauthorized, 'unauthorized');
  let code = '';
  try { code = res.json('code'); } catch (e) { /* 본문이 JSON이 아니면 기타로 센다 */ }
  if (res.status === 503 && code === 'APPLICATION_BUSY') return count(busy, 'busy');
  if (res.status >= 500) return count(serverError, 'server_error');
  switch (code) {
    case 'ALREADY_APPLIED': return count(alreadyApplied, 'already_applied');
    case 'APPLICATION_WINDOW_CLOSED':
      closedBy[bucket].add(1);
      return count(windowClosed, 'window_closed');
    case 'FAN_MEETING_NOT_FOUND': return count(notFound, 'not_found');
    default: return count(otherClientError, 'other_client_error');
  }
}

function count(counter, outcome) {
  counter.add(1);
  return outcome;
}

const OUTCOME_METRICS = [
  'fm_applied', 'fm_already_applied', 'fm_window_closed', 'fm_busy', 'fm_not_found', 'fm_unauthorized',
  'fm_other_client_error', 'fm_server_error', 'fm_transport_error', 'skipped_no_account', 'dropped_iterations',
];
const BUCKET_METRICS = [...BUCKETS.map((b) => `fm_applied_${b}`), ...BUCKETS.map((b) => `fm_window_closed_${b}`)];
const REQUEST_RESULTS = OUTCOME_METRICS.slice(0, 9);

export function handleSummary(data) {
  const value = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const trendOf = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const fmt = (v) => (v === undefined ? '-' : `${v.toFixed(1)}ms`);
  const latency = (t) => `med ${fmt(t.med)}  p95 ${fmt(t['p(95)'])}  p99 ${fmt(t['p(99)'])}  max ${fmt(t.max)}`;
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  const requested = REQUEST_RESULTS.reduce((sum, m) => sum + value(m), 0);
  const clockOffsetMs = data.setup_data ? data.setup_data.clockOffsetMs : null;
  const ref = MODE === 'open' ? 'open' : 'close';
  const lines = [
    '',
    `fanmeeting event ${EVENT_ID}, mode ${MODE}, participants ${PARTICIPANTS} (offset ${USER_OFFSET}), `
      + `peak ${PEAK_RATE}/s (ramp ${RAMP}, hold ${HOLD}), start ${new Date(START_MS).toISOString()}`,
    ...OUTCOME_METRICS.map((m) => `  ${m.padEnd(26)} ${value(m)}`),
    `  requests total             ${requested} (must equal participants ${PARTICIPANTS})`,
    `  by time since ${ref} (laptop clock, server offset ~${clockOffsetMs}ms)`,
    `    ${'applied'.padEnd(16)} ${BUCKETS.map((b) => `${b} ${value(`fm_applied_${b}`)}`).join('  ')}`,
    `    ${'window_closed'.padEnd(16)} ${BUCKETS.map((b) => `${b} ${value(`fm_window_closed_${b}`)}`).join('  ')}`,
    `  k6 latency, client-observed (체감값, 서버 지연은 8081 http.server.requests)`,
    `    all            ${latency(trendOf('fm_apply_duration'))}`,
    `    applied        ${latency(trendOf('fm_apply_duration{outcome:applied}'))}`,
    `  load generator  http_req_connecting p95 ${fmt(trendOf('http_req_connecting')['p(95)'])} max ${fmt(trendOf('http_req_connecting').max)}`
      + `, http_req_blocked p95 ${fmt(trendOf('http_req_blocked')['p(95)'])} max ${fmt(trendOf('http_req_blocked').max)}`,
    `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`,
    '  next: perf/scenarios/fanmeeting-check.sh (invariants must be 0, applications must match fm_applied)',
    '',
  ];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify({
      conditions: { eventId: EVENT_ID, mode: MODE, entryOpensAt: OPENS_AT, entryClosesAt: CLOSES_AT,
        startAt: new Date(START_MS).toISOString(), startOffset: __ENV.START_AT ? null : START_OFFSET,
        participants: PARTICIPANTS, userOffset: USER_OFFSET, peakRate: PEAK_RATE, ramp: RAMP, hold: HOLD,
        preVUs: PRE_VUS, maxVUs: MAX_VUS, baseUrl: BASE_URL, serverClockOffsetMs: clockOffsetMs },
      outcomes: Object.fromEntries(OUTCOME_METRICS.map((m) => [m, value(m)])),
      requestsTotal: requested,
      byTimeSinceReference: Object.fromEntries(BUCKET_METRICS.map((m) => [m, value(m)])),
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
