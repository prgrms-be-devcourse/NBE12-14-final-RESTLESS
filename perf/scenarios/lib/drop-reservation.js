// 드롭 시나리오가 함께 쓰는 부분: 호스트 안전장치, 시드 입력, 요청 한 번과 결과 분류, 집계, 요약.
// 시나리오마다 다른 것은 부하 모델(options.scenarios)과 추가 태그뿐이다. 사용법은 perf/scenarios/README.md.
// 집계 규칙은 docs/testing/drop-performance-measurement.md를 따른다.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
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
// 요청 대상 드롭. 기본은 측정용 핫 드롭이다. 워밍업은 시드의 워밍업 드롭을 넘긴다.
export const HOT = { dropId: DROP_ID, variantIds: VARIANT_IDS };

// "30s", "2m", "7500ms" 형식을 밀리초로 바꾼다.
export function durationMs(d) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m)$/.exec(String(d));
  if (!m) throw new Error(`duration must look like 500ms, 30s or 2m: ${d}`);
  return Math.round(Number(m[1]) * { ms: 1, s: 1000, m: 60000 }[m[2]]);
}

// 재고·대기열 요청의 제한 시간. 제한 시간에 걸린 요청은 이 값으로 평가 지연에 넣는다 (우측 절단).
export const HTTP_TIMEOUT = __ENV.HTTP_TIMEOUT || '5s';
export const HTTP_TIMEOUT_MS = durationMs(HTTP_TIMEOUT);
const K6_REQUEST_TIMEOUT = 1050; // k6 error_code: request timeout

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

// 정상 품절과 업무 거절(409/403), 대기열의 종료된 대기(404)는 http_req_failed에 넣지 않는다.
// 아래는 정합성과 전제 조건 검사다. 지연 목표는 시나리오 요약에서 따로 본다.
export const baseThresholds = {
  drop_reservation_created: [`count<=${TOTAL_STOCK}`],
  drop_reservation_replayed: ['count==0'],
  drop_admission_required: [{ threshold: 'count==0', abortOnFail: true }],
  drop_not_on_sale: [{ threshold: 'count==0', abortOnFail: true }],
  // 판정 없이 결과별 지연을 요약에 남기려고 빈 임계값으로 서브 지표를 만든다.
  'drop_reservation_duration{outcome:created}': [],
  'drop_reservation_duration{outcome:sold_out}': [],
  'drop_eval_latency{api:reserve}': [],
  'drop_unexpected_error{api:reserve}': [],
};

export const summaryTrendStats = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 403, 404, 409));

const created = new Counter('drop_reservation_created');
const replayed = new Counter('drop_reservation_replayed');
const soldOut = new Counter('drop_sold_out');
const activePurchase = new Counter('drop_active_purchase_exists');
const admissionRequired = new Counter('drop_admission_required');
const notOnSale = new Counter('drop_not_on_sale');
const otherClientError = new Counter('drop_other_client_error');
const serverError = new Counter('drop_server_error');
const transportError = new Counter('drop_transport_error');
const timeoutError = new Counter('drop_timeout');
export const skipped = new Counter('skipped_no_account');
// 5xx와 연결 오류, 타임아웃의 비율. 계단식 시나리오가 무릎을 넘으면 멈추는 데 쓴다.
const errorRate = new Rate('drop_error_rate');
// 응답을 받은 요청의 지연. 연결 실패와 타임아웃은 넣지 않는다.
const duration = new Trend('drop_reservation_duration', true);

// 시작한 작업 S와 끝난 작업 F. 미완료 I = S - F. k6 기본 iterations는 시작 수가 아니다.
const iterationStarted = new Counter('iteration_started');
const iterationFinished = new Counter('iteration_finished');
// API별 평가 지연: 응답을 받은 요청(오류 포함)의 지연과, 제한 시간에 걸린 요청의 제한 시간 값.
const evalLatency = new Trend('drop_eval_latency', true);
// API별 예상 밖 오류율: 5xx, 연결 실패, 타임아웃, 예상하지 않은 4xx. 분모는 그 API의 시작한 요청이다.
const unexpectedError = new Rate('drop_unexpected_error');
// 타임아웃이 아닌 전송 실패가 실패하기까지 걸린 시간. 평가 지연에는 넣지 않는다.
const transportFailureTime = new Trend('drop_transport_failure_time', true);

// iteration 본문을 감싼다. 첫 줄에서 시작을 세고 어떤 경로로 끝나든 종료를 센다.
export function tracked(tags, body) {
  iterationStarted.add(1, tags);
  try {
    return body();
  } finally {
    iterationFinished.add(1, tags);
  }
}

// 지금 시각이 시작 시각에서 몇 번째 창인지. 요청은 시작한 창에 귀속한다.
export function windowTag(startMs, sizeSeconds) {
  return String(Math.max(0, Math.floor((Date.now() - startMs) / 1000 / sizeSeconds)));
}

// 전송 결과 종류: response(HTTP 응답 받음), timeout(제한 시간 도달), transport(그 밖의 전송 실패).
export function transportKind(res) {
  if (res.status !== 0) return 'response';
  return res.error_code === K6_REQUEST_TIMEOUT ? 'timeout' : 'transport';
}

// 요청 하나를 평가 지연과 예상 밖 오류율에 기록한다. tags에는 api를 넣는다.
export function observe(res, unexpected, tags, timeoutMs = HTTP_TIMEOUT_MS) {
  const kind = transportKind(res);
  if (kind === 'response') evalLatency.add(res.timings.duration, tags);
  else if (kind === 'timeout') evalLatency.add(timeoutMs, tags);
  else transportFailureTime.add(res.timings.duration, tags);
  unexpectedError.add(unexpected || kind !== 'response', tags);
  return kind;
}

export function authHeaders(user, extra = {}) {
  return { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', ...extra };
}

export function checkTarget() {
  // perf 프로필만 관리 포트에 prometheus를 연다. 응답하지 않으면 perf 앱이 아니므로 멈춘다.
  const metrics = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (metrics.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${metrics.status}`);
  const drop = http.get(`${BASE_URL}/api/drops/${DROP_ID}`, { tags: { name: 'setup' } });
  if (drop.status !== 200) fail(`GET /api/drops/${DROP_ID} returned ${drop.status}`);
  const status = drop.json('saleStatus');
  if (status !== 'ON_SALE') fail(`hot drop ${DROP_ID} saleStatus is ${status}, run seed.sh --refresh`);
}

// 참여자 n의 SKU. 계정 순서대로 SKU를 돌아가며 고른다. 대기 진입과 재고 점유가 같은 값을 써야 한다.
// 입장 허가는 진입한 SKU의 점유에만 쓰이기 때문이다.
export function variantFor(n, target = HOT) {
  return target.variantIds[n % target.variantIds.length];
}

// 재시도. 실제 앱처럼 시간 초과, 연결 오류, 5xx는 같은 Idempotency-Key로 다시 보낸다. 4xx 업무 오류는 보내지 않는다.
// RETRY=0이면 끈다. 회원별 상한 RETRY_MAX는 입장 허가 유효시간(기본 5분)보다 짧게 두고, 관측 종료 시각도 넘지 않는다.
export const RETRY = __ENV.RETRY !== '0';
export const RETRY_MAX = __ENV.RETRY_MAX || '60s';
export const RETRY_MAX_MS = durationMs(RETRY_MAX);
const RETRY_BASE_MS = 200;
const RETRY_CAP_MS = 2000;
// 재시도 요청 수, 재시도로 끝내 성공한 회원 수, 재시도 뒤에도 실패한 회원 수. 태그 api는 reserve, prepare, confirm.
const retryAttempts = new Counter('drop_retry_attempts');
const retryRecovered = new Counter('drop_retry_recovered');
const retryExhausted = new Counter('drop_retry_exhausted');
// 재시도 끝에 4xx 업무 응답(예: 품절)을 받은 회원 수. 회복도 실패도 아니고 서버가 확정한 답이다.
const retryAnswered = new Counter('drop_retry_answered');
// 재시도 요청이 응답을 받은 지연. 첫 시도의 평가 지연(drop_eval_latency)에는 섞지 않는다.
const retryEvalLatency = new Trend('drop_retry_eval_latency', true);
// 첫 시도 시작부터 최종 성공까지의 시간. 태그 retried는 재시도가 있었는지(true, false).
const timeToFinal = new Trend('drop_time_to_final', true);

// 다음 재시도까지 기다릴 밀리초. Retry-After(초)가 있으면 따르고, 없으면 짧은 지수 백오프에 지터를 더한다.
function retryWaitMs(res, attempt) {
  const seconds = Number((res.headers || {})['Retry-After']);
  const base = seconds > 0 ? seconds * 1000 : Math.min(RETRY_CAP_MS, RETRY_BASE_MS * 2 ** attempt);
  return base * (1 + Math.random() * 0.5);
}

// 5xx, 연결 오류, 시간 초과는 결과를 모르거나 서버 쪽 일시 문제라 같은 키로 다시 보낸다.
export function isRetryable(res) { return res.status === 0 || res.status >= 500; }

// attemptFn(attempt)이 {res, ok, retryable}을 돌려주면 retryable이 아닐 때까지 같은 요청을 다시 보낸다.
// deadlineMs까지 회원별 상한 안에서만 기다린다. 첫 시도(attempt 0)의 지연 기록은 attemptFn이 한다.
export function retrying(api, tags, deadlineMs, attemptFn) {
  const startedMs = Date.now();
  const limitMs = Math.min(deadlineMs, startedMs + RETRY_MAX_MS);
  let attempt = 0;
  let r;
  for (;;) {
    r = attemptFn(attempt);
    if (attempt > 0) {
      retryAttempts.add(1, { api, ...tags });
      if (transportKind(r.res) === 'response') retryEvalLatency.add(r.res.timings.duration, { api, ...tags });
    }
    if (r.ok || !r.retryable || !RETRY) break;
    const wait = retryWaitMs(r.res, attempt);
    if (Date.now() + wait > limitMs) break;
    sleep(wait / 1000);
    attempt++;
  }
  if (attempt > 0) (r.ok ? retryRecovered : r.retryable ? retryExhausted : retryAnswered).add(1, { api, ...tags });
  if (r.ok) timeToFinal.add(Date.now() - startedMs, { api, retried: String(attempt > 0), ...tags });
  return { ...r, attempts: attempt + 1 };
}

// 계정 하나가 SKU 하나에 재고 점유를 요청한다. Idempotency-Key는 회원과 드롭마다 하나로 고정이다.
// tags는 요청과 지표에 함께 붙는다 (예: 계단식의 stage, 창 번호 win).
// deadlineMs를 주면 시간 초과, 연결 오류, 5xx를 같은 키로 다시 보낸다. 재시도가 받은 200은 같은 reservationId의 회복이다.
// 평가 지연, 예상 밖 오류율, 응답 지연은 첫 시도만 기록한다.
// 결과 이름, 성공이면 응답 본문({reservationId, orderId, amount, ...}), k6 응답을 돌려준다.
export function reserve(user, n, tags = {}, target = HOT, deadlineMs = null) {
  const variantId = variantFor(n, target);
  const payload = JSON.stringify({ variantId, recipient: `회원${user.id}`, phone: '010-0000-0000', address: '서울시 송파구 올림픽로 424' });
  const params = {
    headers: authHeaders(user, { 'Idempotency-Key': `perf-${target.dropId}-${user.id}` }),
    tags: { name: 'POST /api/drops/{id}/reservations', ...tags },
    timeout: HTTP_TIMEOUT,
  };
  const attemptOnce = (attempt) => {
    const res = http.post(`${BASE_URL}/api/drops/${target.dropId}/reservations`, payload, params);
    const outcome = classify(res, tags, attempt > 0);
    const failed = outcome === 'server_error' || outcome === 'transport_error' || outcome === 'timeout';
    if (attempt === 0) {
      // 정상 결과는 신규 점유와 품절뿐이다. 계정은 한 번만 쓰므로 나머지 거절은 구성이 틀렸다는 뜻이다.
      const unexpected = failed || (outcome !== 'created' && outcome !== 'sold_out');
      if (res.status !== 0) duration.add(res.timings.duration, { outcome, ...tags });
      observe(res, unexpected, { api: 'reserve', ...tags });
      errorRate.add(failed, tags);
      check(res, { 'no server error': () => !failed });
    }
    return { res, outcome, ok: !failed && outcome === 'created', retryable: failed };
  };
  const r = deadlineMs === null ? attemptOnce(0) : retrying('reserve', tags, deadlineMs, attemptOnce);
  let body = null;
  if (r.outcome === 'created') {
    try { body = r.res.json(); } catch (e) { /* 본문이 없으면 결제로 이어 가지 않는다 */ }
  }
  return { outcome: r.outcome, body, res: r.res };
}

// 재시도가 받은 200은 첫 요청을 서버가 이미 처리했다는 뜻이다. 중복 점유가 아니라 같은 점유의 회복이라 created로 센다.
function classify(res, tags, isRetry = false) {
  if (res.status === 0) {
    return transportKind(res) === 'timeout' ? count(timeoutError, 'timeout', tags) : count(transportError, 'transport_error', tags);
  }
  if (res.status === 201) return count(created, 'created', tags);
  if (res.status === 200) return isRetry ? count(created, 'created', tags) : count(replayed, 'replayed', tags);
  if (res.status >= 500) return count(serverError, 'server_error', tags);
  let code = '';
  try { code = res.json('code'); } catch (e) { /* 본문이 JSON이 아니면 기타로 센다 */ }
  switch (code) {
    case 'DROP_SOLD_OUT': return count(soldOut, 'sold_out', tags);
    case 'DROP_ACTIVE_PURCHASE_EXISTS': return count(activePurchase, 'active_purchase', tags);
    case 'DROP_ADMISSION_REQUIRED': return count(admissionRequired, 'admission_required', tags);
    case 'DROP_NOT_ON_SALE': return count(notOnSale, 'not_on_sale', tags);
    default: return count(otherClientError, 'other_client_error', tags);
  }
}

function count(counter, outcome, tags) {
  counter.add(1, tags);
  return outcome;
}

const OUTCOME_METRICS = [
  'drop_reservation_created', 'drop_sold_out', 'drop_active_purchase_exists', 'drop_admission_required',
  'drop_not_on_sale', 'drop_reservation_replayed', 'drop_other_client_error', 'drop_server_error',
  'drop_transport_error', 'drop_timeout', 'skipped_no_account', 'dropped_iterations',
  'iteration_started', 'iteration_finished',
];

export const fmt = (v) => (v === undefined || v === null ? '-' : `${v.toFixed(1)}ms`);
export const pct = (v) => (v === undefined || v === null ? '-' : `${(v * 100).toFixed(2)}%`);
export const latencyLine = (label, v) => `  ${label.padEnd(24)} n ${String(v.count === undefined ? '-' : v.count).padStart(7)}  med ${fmt(v.med)}  p95 ${fmt(v['p(95)'])}  p99 ${fmt(v['p(99)'])}  max ${fmt(v.max)}`;

// 화면 요약과 JSON 요약. headline은 첫 줄, extraLatency는 [이름, 지표 키] 목록으로 지연 줄을 더한다.
// extraLines는 시나리오가 만든 줄, extraOutcomes는 요약 JSON과 화면에 더할 카운터 이름이다.
export function summarize(data, { headline, conditions, extraLatency = [], extraLines = [], extraOutcomes = [] }) {
  const value = (name) => (data.metrics[name] ? data.metrics[name].values.count || 0 : 0);
  const trend = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  const outcomes = [...OUTCOME_METRICS, ...extraOutcomes];
  const started = value('iteration_started');
  const lines = [
    '',
    headline,
    ...outcomes.map((m) => `  ${m.padEnd(30)} ${value(m)}`),
    // 미완료 I = S - F. 종료 로그의 interrupted iterations와 대조한다.
    ...(started ? [`  ${'unfinished (started-finished)'.padEnd(30)} ${started - value('iteration_finished')}`] : []),
    latencyLine('reservation responses', trend('drop_reservation_duration')),
    latencyLine('reservation eval latency', trend('drop_eval_latency{api:reserve}')),
    ...extraLatency.map(([label, key]) => latencyLine(label, trend(key))),
    ...extraLines,
    `  http_req_connecting p95 ${fmt(trend('http_req_connecting')['p(95)'])}, http_req_blocked p95 ${fmt(trend('http_req_blocked')['p(95)'])}`,
    `  http_reqs ${value('http_reqs')}, iterations ${value('iterations')}`,
    `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`,
    '  next: perf/seed/seed.sh --verify (invariant and amount checks must be 0)',
    '',
  ];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify({
      conditions: { dropId: DROP_ID, totalStock: TOTAL_STOCK, baseUrl: BASE_URL, rowOffset: ROW_OFFSET, httpTimeout: HTTP_TIMEOUT, ...conditions },
      outcomes: Object.fromEntries(outcomes.map((m) => [m, value(m)])),
      failedThresholds,
      metrics: data.metrics,
    }, null, 2);
  }
  return out;
}

// 요약에 서브 지표를 남기려고 빈 임계값을 만든다. keys는 "지표{태그:값}" 목록이다.
export function emptyThresholds(keys) {
  return Object.fromEntries(keys.map((k) => [k, []]));
}
