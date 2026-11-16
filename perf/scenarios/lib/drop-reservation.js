// 드롭 재고 점유 시나리오가 함께 쓰는 부분: 호스트 안전장치, 시드 입력, 요청 한 번과 결과 분류, 요약.
// 시나리오마다 다른 것은 부하 모델(options.scenarios)과 추가 태그뿐이다. 사용법은 perf/scenarios/README.md.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

export const SEED_OUT = __ENV.SEED_OUT || '../seed/out';
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
export const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://localhost:8081').replace(/\/$/, '');
export const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';

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

export const manifest = JSON.parse(open(`${SEED_OUT}/manifest.json`));
export const DROP_ID = manifest.drop.hot_drop_id;
export const VARIANT_IDS = manifest.drop.variants.map((v) => v.variant_id);
export const TOTAL_STOCK = manifest.drop.total_stock;

// users.csv에서 ROW_OFFSET행을 건너뛰고 count행을 읽는다. 행이 모자라면 시작하지 않는다.
// 같은 시드로 여러 번 실행할 때 앞 실행이 쓴 계정을 건너뛰려고 ROW_OFFSET을 쓴다. 다시 쓰면 같은 키 재요청이 된다.
export const ROW_OFFSET = Number(__ENV.ROW_OFFSET || 0);
export function loadUsers(count) {
  const users = new SharedArray('users', () => {
    const lines = open(`${SEED_OUT}/users.csv`).split('\n');
    const header = lines[0].split(',');
    const idx = { id: header.indexOf('user_id'), token: header.indexOf('access_token') };
    const rows = [];
    let skippedRows = 0;
    for (let i = 1; i < lines.length && rows.length < count; i++) {
      if (!lines[i]) continue;
      if (skippedRows < ROW_OFFSET) { skippedRows++; continue; }
      const cols = lines[i].split(',');
      rows.push({ id: cols[idx.id], token: cols[idx.token] });
    }
    return rows;
  });
  if (users.length < count) {
    throw new Error(`users.csv has ${users.length} rows after ROW_OFFSET ${ROW_OFFSET}, need ${count}`);
  }
  return users;
}

// 정상 품절과 업무 거절(409/403)은 http_req_failed에 넣지 않는다. 5xx와 연결 오류만 실패다.
// 지연 목표 수치는 미측정이라 임계값을 두지 않는다. 아래는 정합성과 전제 조건 검사다.
export const baseThresholds = {
  drop_reservation_created: [`count<=${TOTAL_STOCK}`],
  drop_reservation_replayed: ['count==0'],
  drop_admission_required: [{ threshold: 'count==0', abortOnFail: true }],
  drop_not_on_sale: [{ threshold: 'count==0', abortOnFail: true }],
  // 판정 없이 결과별 지연을 요약에 남기려고 빈 임계값으로 서브 지표를 만든다.
  'drop_reservation_duration{outcome:created}': [],
  'drop_reservation_duration{outcome:sold_out}': [],
};

export const summaryTrendStats = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'];

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
export const skipped = new Counter('skipped_no_account');
// 5xx와 연결 오류, 타임아웃의 비율. 계단식 시나리오가 무릎을 넘으면 멈추는 데 쓴다.
const errorRate = new Rate('drop_error_rate');
const duration = new Trend('drop_reservation_duration', true);

export function checkTarget() {
  // perf 프로필만 관리 포트에 prometheus를 연다. 응답하지 않으면 perf 앱이 아니므로 멈춘다.
  const metrics = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${metrics.status}`);
  const drop = http.get(`${BASE_URL}/api/drops/${DROP_ID}`, { tags: { name: 'setup' } });
  if (drop.status !== 200) fail(`GET /api/drops/${DROP_ID} returned ${drop.status}`);
  const status = drop.json('saleStatus');
  if (status !== 'ON_SALE') fail(`hot drop ${DROP_ID} saleStatus is ${status}, run seed.sh --refresh`);
}

// 계정 하나가 SKU 하나에 재고 점유를 한 번 요청한다. Idempotency-Key는 계정마다 하나다.
// tags는 요청과 지연 지표에 함께 붙는다 (예: 계단식의 stage).
export function reserve(user, n, tags = {}) {
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
      tags: { name: 'POST /api/drops/{id}/reservations', ...tags },
    },
  );

  const outcome = classify(res);
  const failed = outcome === 'server_error' || outcome === 'transport_error';
  duration.add(res.timings.duration, { outcome, ...tags });
  errorRate.add(failed, tags);
  check(res, { 'no server error': () => !failed });
  return outcome;
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

const fmt = (v) => (v === undefined ? '-' : `${v.toFixed(1)}ms`);
const latencyLine = (label, v) => `  ${label.padEnd(20)} med ${fmt(v.med)}  p95 ${fmt(v['p(95)'])}  p99 ${fmt(v['p(99)'])}  max ${fmt(v.max)}`;

// 화면 요약과 JSON 요약. headline은 첫 줄, extraLatency는 [이름, 지표 키] 목록으로 지연 줄을 더한다.
export function summarize(data, { headline, conditions, extraLatency = [] }) {
  const value = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const trend = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  const lines = [
    '',
    headline,
    ...OUTCOME_METRICS.map((m) => `  ${m.padEnd(30)} ${value(m)}`),
    latencyLine('reservation latency', trend('drop_reservation_duration')),
    ...extraLatency.map(([label, key]) => latencyLine(label, trend(key))),
    `  http_req_connecting p95 ${fmt(trend('http_req_connecting')['p(95)'])}, http_req_blocked p95 ${fmt(trend('http_req_blocked')['p(95)'])}`,
    `  http_reqs ${value('http_reqs')}, iterations ${value('iterations')}`,
    `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`,
    '  next: perf/seed/seed.sh --verify (invariant and amount checks must be 0)',
    '',
  ];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify({
      conditions: { dropId: DROP_ID, totalStock: TOTAL_STOCK, baseUrl: BASE_URL, rowOffset: ROW_OFFSET, ...conditions },
      outcomes: Object.fromEntries(OUTCOME_METRICS.map((m) => [m, value(m)])),
      failedThresholds,
      metrics: data.metrics,
    }, null, 2);
  }
  return out;
}
