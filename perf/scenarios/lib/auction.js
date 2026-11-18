// Shared auction load-test setup. This library deliberately has no seed-file mode.
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { fail, sleep } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://perf-local:8080').replace(/\/$/, '');
export const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://perf-local:8081').replace(/\/$/, '');
export const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';
export const PERF_APP_HOST = __ENV.PERF_APP_HOST || hostOf(BASE_URL);
export const allowedHosts = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map((s) => s.trim()).filter(Boolean);
const hostPattern = /^perf-[a-z0-9][a-z0-9.-]*$/i;
for (const host of [hostOf(BASE_URL), hostOf(MANAGEMENT_URL), PERF_APP_HOST]) {
  if (!hostPattern.test(host) || /prod/i.test(host) || !allowedHosts.includes(host)) {
    throw new Error(`host ${host} is not an allowed perf host`);
  }
}
http.setResponseCallback(http.expectedStatuses(200, 201, 400, 401, 403, 404, 409, 422, 429));

export const summaryTrendStats = ['min', 'med', 'avg', 'p(50)', 'p(95)', 'p(99)', 'max'];
const configuredPhases = (__ENV.PHASES || '8000,10000').split(',').map((value) => Number(value.trim()));
const phaseStatusValues = [0, 200, 201, 400, 401, 403, 404, 409, 422, 429, 500, 501, 502, 503, 504];
const phaseErrorValues = ['BID_AMOUNT_TOO_LOW', 'BID_ALREADY_HIGHEST', 'AUCTION_CLOSED', 'AUCTION_NOT_OPEN',
  'CREDIT_INSUFFICIENT', 'BID_BY_SELLER', 'AUCTION_NOT_FOUND', 'AUCTION_RESULT_NOT_FOUND',
  'IDEMPOTENCY_KEY_CONFLICT', 'INVALID_REQUEST', 'UNAUTHORIZED', 'FORBIDDEN'];
const phaseOutcomeValues = ['accepted', 'connection', '5xx', 'public_get_blocked', 'BID_AMOUNT_TOO_LOW',
  'BID_ALREADY_HIGHEST', 'AUCTION_CLOSED', 'AUCTION_NOT_OPEN', 'CREDIT_INSUFFICIENT', 'BID_BY_SELLER',
  'AUCTION_NOT_FOUND', 'AUCTION_RESULT_NOT_FOUND', 'IDEMPOTENCY_KEY_CONFLICT', 'INVALID_REQUEST',
  'http_200', 'http_201', 'http_400', 'http_401', 'http_403', 'http_404', 'http_409', 'other'];
export const phaseMetrics = Object.fromEntries([...new Set(configuredPhases)].filter(Number.isSafeInteger).map((phase) => {
  const prefix = `auction_phase_${phase}`;
  const counters = (names) => Object.fromEntries(names.map((name) => [name, new Counter(`${prefix}_${name}`)]));
  return [String(phase), {
    prefix, statusValues: phaseStatusValues, errorValues: phaseErrorValues, outcomeValues: phaseOutcomeValues,
    sent: new Counter(`${prefix}_sent`), received: new Counter(`${prefix}_received`),
    transport: new Counter(`${prefix}_transport_failed`), fiveXx: new Counter(`${prefix}_5xx`),
    accepted: new Counter(`${prefix}_accepted`), bidServerAccepted: new Trend(`${prefix}_server_accepted`),
    closed: new Counter(`${prefix}_closed`), closedBeforeEnd: new Counter(`${prefix}_closed_before_original_end`),
    latency: new Trend(`${prefix}_response_latency`, true), sendOffset: new Trend(`${prefix}_send_offset_ms`, true),
    responseFromT: new Trend(`${prefix}_response_from_t_ms`, true), withinOneSecond: new Rate(`${prefix}_within_one_second`),
    statuses: counters(phaseStatusValues.map((value) => `status_${value}`).concat('status_other')),
    errors: Object.fromEntries(phaseErrorValues.map((code) => [code, new Counter(`${prefix}_error_${code}`)]).concat([
      ['error_other', new Counter(`${prefix}_error_other`)],
    ])),
    outcomes: Object.fromEntries(phaseOutcomeValues.map((outcome) => [outcome, new Counter(`${prefix}_outcome_${outcome}`)])),
    targetId: new Trend(`${prefix}_target_id`),
    phaseStart: new Trend(`${prefix}_start_ms`), serverOffset: new Trend(`${prefix}_server_offset_ms`),
    latestAcceptedAt: new Trend(`${prefix}_latest_accepted_at_ms`),
    latestAcceptedAmount: new Trend(`${prefix}_latest_accepted_amount`), resultSold: new Counter(`${prefix}_result_sold`),
    resultUnsold: new Counter(`${prefix}_result_unsold`), resultAmount: new Trend(`${prefix}_result_amount`),
    resultCloseLag: new Trend(`${prefix}_result_close_lag_ms`), resultExtension: new Trend(`${prefix}_result_extension_ms`),
    resultInitialEnd: new Trend(`${prefix}_result_initial_end_ms`), resultFinalEnd: new Trend(`${prefix}_result_final_end_ms`),
    resultFinalizedAt: new Trend(`${prefix}_result_finalized_at_ms`), resultHighestBidAmount: new Trend(`${prefix}_result_highest_bid_amount`),
    resultMatchesHighest: new Rate(`${prefix}_result_matches_highest`), resultMissing: new Counter(`${prefix}_result_missing`),
  }];
}));
export function phaseMetric(phase) {
  const metrics = phaseMetrics[String(phase)];
  if (!metrics) fail(`phase ${phase} has no registered summary metrics; align PHASES with k6 options`);
  return metrics;
}
export const loginSuccess = new Rate('auction_login_success');
export const loginDuration = new Trend('auction_login_duration', true);
export const loginLate = new Counter('auction_login_late');
export const loginSpanSeconds = new Trend('auction_login_span_seconds');
export const requestSent = new Counter('auction_request_sent');
export const responseReceived = new Counter('auction_response_received');
export const transportFailed = new Counter('auction_transport_failed');
export const serverFailed = new Counter('auction_server_failed');
export const requestLatency = new Trend('auction_request_duration', true);
export const responseLatency = new Trend('auction_response_latency', true);
export const targetAuctionId = new Trend('auction_target_id');
export const phaseStartMetric = new Trend('auction_phase_start_ms');
export const serverOffsetMetric = new Trend('auction_server_clock_offset_ms');
export const sendOffset = new Trend('auction_send_offset_ms', true);
export const responseFromT = new Trend('auction_response_from_t_ms', true);
export const withinOneSecond = new Rate('auction_response_within_one_second');
export const bidAccepted = new Counter('auction_bid_accepted');
export const bidServerAccepted = new Trend('auction_bid_server_accepted');
export const closeBeforeEnd = new Counter('auction_bid_closed_before_original_end');
export const closeTotal = new Counter('auction_bid_closed_total');
export const resultSold = new Counter('auction_result_sold');
export const resultUnsold = new Counter('auction_result_unsold');
export const resultAmount = new Trend('auction_result_amount');
export const resultCloseLag = new Trend('auction_result_close_lag_ms');
export const resultExtension = new Trend('auction_result_extension_ms');
export const resultInitialEnd = new Trend('auction_result_initial_end_ms');
export const resultFinalEnd = new Trend('auction_result_final_end_ms');
export const resultFinalizedAt = new Trend('auction_result_finalized_at_ms');
export const resultHighestBidAmount = new Trend('auction_result_highest_bid_amount');
export const resultMatchesHighest = new Rate('auction_result_matches_highest');

const outcomes = new Counter('auction_result_by_outcome');
const errorCodes = new Counter('auction_error_code');
const statusCodes = new Counter('auction_http_status');

function hostOf(url) {
  return url.replace(/^https?:\/\//, '').split(/[/:]/)[0];
}

export function checkTarget() {
  const res = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup/actuator' }, timeout: '5s' });
  if (res.status !== 200) fail(`management /actuator/prometheus returned HTTP ${res.status}`);
}

export function tryLogin(email, password) {
  const before = Date.now();
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup/login' }, timeout: '10s',
  });
  let token;
  try { token = res.json('accessToken'); } catch (_) { /* invalid credentials or non-JSON response */ }
  return { token: res.status === 200 ? token : null, status: res.status, elapsedMs: Date.now() - before };
}

export function login(email, password) {
  const result = tryLogin(email, password);
  if (!result.token) fail(`login failed: HTTP ${result.status}`);
  return result;
}

export function loginOrganizer(email, password) {
  return login(email, password).token;
}

export function detail(id, token = '') {
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  const res = http.get(`${BASE_URL}/api/auctions/${id}`, { headers, tags: { name: 'setup/detail' }, timeout: '10s' });
  if (res.status !== 200) fail(`GET auction ${id} returned HTTP ${res.status}`);
  const body = res.json();
  if (!Number.isSafeInteger(body.revision) || !Number.isFinite(body.minimumBidAmount)
      || !Number.isFinite(body.minimumIncrementAmount) || !body.serverTime || !body.endsAt) {
    fail(`auction ${id}: invalid detail response`);
  }
  return body;
}

export function createAuction(token, terms) {
  const res = http.post(`${BASE_URL}/api/auctions`, JSON.stringify(terms), {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    tags: { name: 'setup/create-auction' }, timeout: '10s',
  });
  let id;
  try { id = res.json('auctionId'); } catch (_) { /* report status below */ }
  if (res.status !== 201 || !Number.isSafeInteger(id) || id < 1) fail(`auction creation failed: HTTP ${res.status}`);
  return id;
}

export function waitUntilOpen(id, deadlineServerMs) {
  while (true) {
    const d = detail(id);
    if (d.status === 'OPEN') return d;
    if (Date.parse(d.serverTime) > deadlineServerMs) {
      fail(`auction ${id} did not open before deadline; check that the auction scheduler is running`);
    }
    // k6 sleep is intentionally short: each poll is setup traffic, not measured load.
    // eslint-disable-next-line no-undef
    sleep(1);
  }
}

export function bid(user, id, amount, phase, phaseStartMs, timeoutSeconds, originalEndsAt = null, serverOffsetMs = 0) {
  const phaseSummary = phaseMetric(phase);
  const sentAt = Date.now();
  requestSent.add(1, { phase, api: 'bid' });
  phaseSummary.sent.add(1);
  phaseSummary.sendOffset.add(sentAt - phaseStartMs);
  sendOffset.add(sentAt - phaseStartMs, { phase });
  const res = http.post(`${BASE_URL}/api/auctions/${id}/bids`, JSON.stringify({ amount }), {
    headers: { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    tags: { name: 'POST /api/auctions/{id}/bids', phase, api: 'bid' }, timeout: `${timeoutSeconds}s`,
  });
  const receivedAt = Date.now();
  const tags = { phase, api: 'bid' };
  recordResponse(res, receivedAt, sentAt, phase, phaseStartMs, 'bid');
  let body = {};
  try { body = res.json() || {}; } catch (_) { /* connection or server error */ }
  const status = res.status;
  const outcome = status === 0 ? 'connection' : status >= 500 ? '5xx'
    : status === 201 ? 'accepted' : (body.code || `http_${status}`);
  outcomes.add(1, { ...tags, outcome });
  phaseSummary.outcomes[outcome] ? phaseSummary.outcomes[outcome].add(1) : phaseSummary.outcomes.other.add(1);
  if (body.code) {
    errorCodes.add(1, { ...tags, code: body.code });
    phaseSummary.errors[body.code] ? phaseSummary.errors[body.code].add(1) : phaseSummary.errors.error_other.add(1);
  }
  if (status === 201) {
    bidAccepted.add(1, tags);
    phaseSummary.accepted.add(1);
    const acceptedAt = Date.parse(body.acceptedAt);
    if (Number.isFinite(acceptedAt) && Number.isSafeInteger(body.bidId) && body.bidId > 0) {
      // Accepted bid prices increase strictly, so the highest accepted response is the latest one.
      phaseSummary.latestAcceptedAt.add(acceptedAt);
      phaseSummary.latestAcceptedAmount.add(body.amount);
    }
  }
  if (status === 409 && body.code === 'AUCTION_CLOSED') {
    closeTotal.add(1, tags);
    phaseSummary.closed.add(1);
    if (originalEndsAt && sentAt + serverOffsetMs < Date.parse(originalEndsAt)) {
      closeBeforeEnd.add(1, tags);
      phaseSummary.closedBeforeEnd.add(1);
    }
  }
  return { status, body, outcome };
}

export function getMeasured(path, phase, phaseStartMs, api) {
  const phaseSummary = phaseMetric(phase);
  const sentAt = Date.now();
  requestSent.add(1, { phase, api });
  phaseSummary.sent.add(1);
  phaseSummary.sendOffset.add(sentAt - phaseStartMs);
  sendOffset.add(sentAt - phaseStartMs, { phase });
  const res = http.get(`${BASE_URL}${path}`, { tags: { name: `GET ${api}`, phase, api }, timeout: `${__ENV.REQUEST_TIMEOUT_SECONDS || 60}s` });
  recordResponse(res, Date.now(), sentAt, phase, phaseStartMs, api);
  let body = {};
  try { body = res.json() || {}; } catch (_) { /* empty or non-JSON response */ }
  const outcome = res.status === 0 ? 'connection' : res.status >= 500 ? '5xx'
    : res.status === 401 ? 'public_get_blocked' : body.code || `http_${res.status}`;
  outcomes.add(1, { phase, api, outcome });
  phaseSummary.outcomes[outcome] ? phaseSummary.outcomes[outcome].add(1) : phaseSummary.outcomes.other.add(1);
  if (body.code) {
    errorCodes.add(1, { phase, api, code: body.code });
    phaseSummary.errors[body.code] ? phaseSummary.errors[body.code].add(1) : phaseSummary.errors.error_other.add(1);
  }
  return { status: res.status, body, outcome };
}

export function recordLogin(success, elapsedMs, isLate, spanSeconds) {
  loginSuccess.add(success);
  loginDuration.add(elapsedMs);
  loginSpanSeconds.add(spanSeconds);
  if (isLate) loginLate.add(1);
}

function recordResponse(res, receivedAt, sentAt, phase, phaseStartMs, api) {
  const phaseSummary = phaseMetric(phase);
  statusCodes.add(1, { phase, api, status: String(res.status) });
  const statusMetric = phaseSummary.statuses[`status_${res.status}`] || phaseSummary.statuses.status_other;
  statusMetric.add(1);
  if (res.status === 0) { transportFailed.add(1, { phase, api }); phaseSummary.transport.add(1); }
  else {
    responseReceived.add(1, { phase, api });
    phaseSummary.received.add(1);
    if (res.status >= 500) { serverFailed.add(1, { phase, api }); phaseSummary.fiveXx.add(1); }
  }
  const start = phaseStartMs;
  requestLatency.add(res.timings?.duration || receivedAt - sentAt, { phase, api, status: String(res.status) });
  responseLatency.add(res.timings?.duration || receivedAt - sentAt, { phase });
  phaseSummary.latency.add(res.timings?.duration || receivedAt - sentAt);
  if (res.status !== 0) {
  if (res.status !== 0) {
    responseFromT.add(receivedAt - start, { phase, api });
    phaseSummary.responseFromT.add(receivedAt - start);
  }
  }
  phaseSummary.withinOneSecond.add(res.status !== 0 && receivedAt - start <= 1000);
  withinOneSecond.add(res.status !== 0 && receivedAt - start <= 1000, { phase, api });
}

export function summarize(data, conditions) {
  const values = (name) => data.metrics[name]?.values || {};
  const phases = (conditions.phases || []).map((phase) => {
    const metrics = phaseMetric(phase);
    const p = metrics.prefix;
    const statusRows = metrics.statusValues.map((status) => ({ status, count: values(p + '_status_' + status).count || 0 })).filter((row) => row.count);
    const otherStatus = values(p + '_status_other').count || 0;
    if (otherStatus) statusRows.push({ status: 'other', count: otherStatus });
    const errors = metrics.errorValues.map((code) => ({ code, count: values(p + '_error_' + code).count || 0 })).filter((row) => row.count);
    const otherErrors = values(p + '_error_other').count || 0;
    if (otherErrors) errors.push({ code: 'OTHER', count: otherErrors });
    const outcomes = metrics.outcomeValues.map((outcome) => ({ outcome, count: values(p + '_outcome_' + outcome).count || 0 })).filter((row) => row.count);
    const latency = values(p + '_response_latency');
    const offset = values(p + '_send_offset_ms');
    const within = values(p + '_within_one_second');
    const sentAt = values(p + '_start_ms').min;
    return { phase, phaseStartsAt: sentAt === undefined ? null : new Date(sentAt).toISOString(),
      serverClockOffsetMs: values(p + '_server_offset_ms').min ?? null,
      sent: values(p + '_sent').count || 0, received: values(p + '_received').count || 0,
      connectionFailures: values(p + '_transport_failed').count || 0, fiveXx: values(p + '_5xx').count || 0,
      accepted: values(p + '_accepted').count || 0, statuses: statusRows, errors, outcomes,
      responseLatencyMs: { p50: latency.med ?? null, p95: latency['p(95)'] ?? null,
        p99: latency['p(99)'] ?? null, max: latency.max ?? null },
      responseWithinOneSecond: within.rate ?? null, lastResponseFromTMs: values(p + '_response_from_t_ms').max ?? null,
      sendSpreadMs: { p50: offset.med ?? null, max: offset.max ?? null },
      auctionId: values(p + '_target_id').min ?? null };
  });
  const errorsByPhase = phases.map((p) => ({ phase: p.phase, errors: p.errors }));
  const allLatency = values('auction_request_duration');
  const allWithin = values('auction_response_within_one_second');
  const result = { conditions: { ...conditions, baseUrl: BASE_URL, allowedAppHost: PERF_APP_HOST }, phases,
    login: { requested: conditions.loginRequested || 0, succeeded: values('auction_login_success').passes || 0,
      seconds: values('auction_login_span_seconds').max ?? null, late: values('auction_login_late').count || 0 },
    totals: { sent: values('auction_request_sent').count || 0, received: values('auction_response_received').count || 0,
      connectionFailures: values('auction_transport_failed').count || 0, fiveXx: values('auction_server_failed').count || 0,
      errorsByPhase, responseLatencyMs: { p50: allLatency.med ?? null, p95: allLatency['p(95)'] ?? null,
        p99: allLatency['p(99)'] ?? null, max: allLatency.max ?? null }, responseWithinOneSecond: allWithin.rate ?? null },
    correctnessWarnings: [] };
  if (result.totals.fiveXx || result.totals.connectionFailures) result.correctnessWarnings.push('5xx or connection failures are non-zero');
  if (conditions.target && phases.some((p) => p.outcomes.some((o) => o.outcome === 'public_get_blocked' && o.count > 0))) {
    result.correctnessWarnings.push('public GET is blocked (401)');
  }
  const warning = result.correctnessWarnings.length ? '\n!! 정확성 확인 필요: ' + result.correctnessWarnings.join('; ') : '';
  const lines = ['auction ' + conditions.target + ': ' + BASE_URL, ...phases.map((p) =>
    '  phase ' + p.phase + ': sent=' + p.sent + ' received=' + p.received + ' connection=' + p.connectionFailures
      + ' 5xx=' + p.fiveXx + ' accepted=' + p.accepted + ' latency=' + JSON.stringify(p.responseLatencyMs)
      + ' <=1s=' + (p.responseWithinOneSecond ?? '-') + ' lastFromT=' + (p.lastResponseFromTMs ?? '-')
      + 'ms sendSpread=' + JSON.stringify(p.sendSpreadMs)),
    '  statuses/errors: ' + JSON.stringify(phases.map((p) => ({ phase: p.phase, statuses: p.statuses, errors: p.errors }))), warning, ''];
  result.stdout = lines.join('\n');
  return result;
}
