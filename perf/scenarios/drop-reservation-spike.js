// EXP-01 쓰기 기준선: 판매 시작 순간 핫 드롭 재고 점유 스파이크. 사용법은 perf/scenarios/README.md.
//
// 대기열 없이 POST /api/drops/{id}/reservations만 친다. 앱은 restless.drop.queue.required=false로 띄운다.
// 반복마다 users.csv의 다른 행을 쓴다. 한 계정은 테스트 전체에서 한 번만 요청한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const SEED_OUT = __ENV.SEED_OUT || '../seed/out';
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://localhost:8081').replace(/\/$/, '');
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';

// 배포 서버에 부하를 주지 않도록 perf로 시작하고 허용 목록에 정확히 있는 호스트만 친다 (seed.sh와 같은 방식).
// 성능 테스트 호스트만 perf-를 붙인다. IP로 치려면 /etc/hosts나 docker --add-host로 perf- 이름을 붙인다.
const allowedHosts = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map((h) => h.trim()).filter(Boolean);
for (const url of [BASE_URL, MANAGEMENT_URL]) {
  const host = url.replace(/^https?:\/\//, '').split(/[:/]/)[0];
  if (!/^perf-/i.test(host)) throw new Error(`host ${host} must start with perf- (e.g. perf-app)`);
  if (!allowedHosts.includes(host)) {
    throw new Error(`host ${host} is not in PERF_ALLOWED_APP_HOSTS`);
  }
  if (/prod/i.test(host)) throw new Error(`host ${host} looks like production`);
}

const manifest = JSON.parse(open(`${SEED_OUT}/manifest.json`));
const DROP_ID = manifest.drop.hot_drop_id;
const VARIANT_IDS = manifest.drop.variants.map((v) => v.variant_id);
const TOTAL_STOCK = manifest.drop.total_stock;

// 경쟁률 N:1 → 참여자 = 총재고 x N. CSV 앞에서부터 그만큼만 쓴다.
const RATIO = Number(__ENV.RATIO || manifest.contention_ratio);
const PARTICIPANTS = Number(__ENV.PARTICIPANTS || TOTAL_STOCK * RATIO);

const users = new SharedArray('users', () => {
  const lines = open(`${SEED_OUT}/users.csv`).split('\n');
  const header = lines[0].split(',');
  const idx = { id: header.indexOf('user_id'), token: header.indexOf('access_token') };
  const rows = [];
  for (let i = 1; i < lines.length && rows.length < PARTICIPANTS; i++) {
    if (!lines[i]) continue;
    const cols = lines[i].split(',');
    rows.push({ id: cols[idx.id], token: cols[idx.token] });
  }
  return rows;
});
if (users.length < PARTICIPANTS) {
  throw new Error(`users.csv has ${users.length} rows, need ${PARTICIPANTS}`);
}

// 부하 모델: 판매 시작 순간 몰리는 스파이크. 도착률(초당 반복)을 짧게 올려 유지한 뒤 내린다.
// 총 도착 수가 참여자보다 많으면 남는 반복은 요청 없이 끝난다 (skipped_no_account).
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const RAMP = __ENV.RAMP || '5s';
const HOLD = __ENV.HOLD || '20s';
const PRE_VUS = Number(__ENV.PRE_VUS || 500);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);

export const options = {
  scenarios: {
    drop_spike: {
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
  // 정상 품절과 업무 거절(409/403)은 http_req_failed에 넣지 않는다. 5xx와 연결 오류만 실패다.
  // 지연 목표 수치는 미측정이라 임계값을 두지 않는다. 아래는 정합성과 전제 조건 검사다.
  thresholds: {
    drop_reservation_created: [`count<=${TOTAL_STOCK}`],
    drop_reservation_replayed: ['count==0'],
    drop_admission_required: [{ threshold: 'count==0', abortOnFail: true }],
    drop_not_on_sale: [{ threshold: 'count==0', abortOnFail: true }],
    // 판정 없이 결과별 지연을 요약에 남기려고 빈 임계값으로 서브 지표를 만든다.
    'drop_reservation_duration{outcome:created}': [],
    'drop_reservation_duration{outcome:sold_out}': [],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(200, 201, 403, 409));

const created = new Counter('drop_reservation_created');
const replayed = new Counter('drop_reservation_replayed');
const soldOut = new Counter('drop_sold_out');
const activePurchase = new Counter('drop_active_purchase_exists');
const admissionRequired = new Counter('drop_admission_required');
const notOnSale = new Counter('drop_not_on_sale');
const otherClientError = new Counter('drop_other_client_error');
const serverError = new Counter('drop_server_error');
const transportError = new Counter('drop_transport_error');
const skipped = new Counter('skipped_no_account');
const duration = new Trend('drop_reservation_duration', true);

export function setup() {
  // perf 프로필만 관리 포트에 prometheus를 연다. 응답하지 않으면 perf 앱이 아니므로 멈춘다.
  const metrics = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${metrics.status}`);
  const drop = http.get(`${BASE_URL}/api/drops/${DROP_ID}`, { tags: { name: 'setup' } });
  if (drop.status !== 200) fail(`GET /api/drops/${DROP_ID} returned ${drop.status}`);
  const status = drop.json('saleStatus');
  if (status !== 'ON_SALE') fail(`hot drop ${DROP_ID} saleStatus is ${status}, run seed.sh --refresh`);
  console.log(`drop=${DROP_ID} variants=${VARIANT_IDS} stock=${TOTAL_STOCK} participants=${PARTICIPANTS} peak=${PEAK_RATE}/s`);
}

export default function () {
  const n = exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  const user = users[n];
  const variantId = VARIANT_IDS[n % VARIANT_IDS.length];
  const res = http.post(
    `${BASE_URL}/api/drops/${DROP_ID}/reservations`,
    JSON.stringify({ variantId, recipient: `회원${user.id}`, phone: '010-0000-0000', address: '서울시 송파구 올림픽로 424' }),
    {
      headers: {
        Authorization: `Bearer ${user.token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `perf-${DROP_ID}-${user.id}`,
      },
      tags: { name: 'POST /api/drops/{id}/reservations' },
    },
  );

  const outcome = classify(res);
  duration.add(res.timings.duration, { outcome });
  check(res, { 'no server error': () => outcome !== 'server_error' && outcome !== 'transport_error' });
}

function classify(res) {
  if (res.status === 0) return count(transportError, 'transport_error');
  if (res.status === 201) return count(created, 'created');
  if (res.status === 200) return count(replayed, 'replayed');
  if (res.status >= 500) return count(serverError, 'server_error');
  let code = '';
  try { code = res.json('code'); } catch (e) { /* 본문이 JSON이 아니면 기타로 센다 */ }
  switch (code) {
    case 'DROP_SOLD_OUT': return count(soldOut, 'sold_out');
    case 'DROP_ACTIVE_PURCHASE_EXISTS': return count(activePurchase, 'active_purchase');
    case 'DROP_ADMISSION_REQUIRED': return count(admissionRequired, 'admission_required');
    case 'DROP_NOT_ON_SALE': return count(notOnSale, 'not_on_sale');
    default: return count(otherClientError, 'other_client_error');
  }
}

function count(counter, outcome) {
  counter.add(1);
  return outcome;
}

const OUTCOME_METRICS = [
  'drop_reservation_created', 'drop_sold_out', 'drop_active_purchase_exists', 'drop_admission_required',
  'drop_not_on_sale', 'drop_reservation_replayed', 'drop_other_client_error', 'drop_server_error',
  'drop_transport_error', 'skipped_no_account', 'dropped_iterations',
];

export function handleSummary(data) {
  const value = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const trend = data.metrics.drop_reservation_duration ? data.metrics.drop_reservation_duration.values : {};
  const fmt = (v) => (v === undefined ? '-' : `${v.toFixed(1)}ms`);
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  const lines = [
    '',
    `drop ${DROP_ID}, stock ${TOTAL_STOCK}, participants ${PARTICIPANTS}, peak ${PEAK_RATE}/s (ramp ${RAMP}, hold ${HOLD})`,
    ...OUTCOME_METRICS.map((m) => `  ${m.padEnd(30)} ${value(m)}`),
    `  reservation latency  med ${fmt(trend.med)}  p95 ${fmt(trend['p(95)'])}  p99 ${fmt(trend['p(99)'])}  max ${fmt(trend.max)}`,
    `  http_reqs ${value('http_reqs')}, iterations ${value('iterations')}`,
    `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`,
    '  next: perf/seed/seed.sh --verify (invariant and amount checks must be 0)',
    '',
  ];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify({
      conditions: { dropId: DROP_ID, totalStock: TOTAL_STOCK, participants: PARTICIPANTS, ratio: RATIO,
        peakRate: PEAK_RATE, ramp: RAMP, hold: HOLD, preVUs: PRE_VUS, maxVUs: MAX_VUS, baseUrl: BASE_URL },
      outcomes: Object.fromEntries(OUTCOME_METRICS.map((m) => [m, value(m)])),
      failedThresholds,
      metrics: data.metrics,
    }, null, 2);
  }
  return out;
}
