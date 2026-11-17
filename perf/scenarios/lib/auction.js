// Shared auction load-test input, target guard, request classification and summary.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

export const SETUP = __ENV.SETUP || 'manifest';
if (!['manifest', 'self'].includes(SETUP)) throw new Error(`invalid SETUP: ${SETUP}`);
export const SEED_OUT = __ENV.SEED_OUT || '../seed/out';
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
export const MANAGEMENT_URL = (__ENV.MANAGEMENT_URL || 'http://localhost:8081').replace(/\/$/, '');
export const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';
export const ROW_OFFSET = Number(__ENV.ROW_OFFSET || 0);
const allowed = (__ENV.PERF_ALLOWED_APP_HOSTS || '').split(',').map((s) => s.trim()).filter(Boolean);
for (const url of [BASE_URL, MANAGEMENT_URL]) {
  const host = url.replace(/^https?:\/\//, '').split(/[:/]/)[0];
  if (!/^perf-/i.test(host) || /prod/i.test(host) || !allowed.includes(host)) throw new Error(`host ${host} is not an allowed perf host`);
}
export const manifest = SETUP === 'manifest' ? JSON.parse(open(`${SEED_OUT}/manifest.json`)) : null;
export const auction = manifest?.auction || null;
if (SETUP === 'manifest' && (!auction || !Number.isInteger(auction.mass_first_user_id) || auction.mass_first_user_id <= 1)) throw new Error('manifest auction account ranges are missing');
if (!Number.isInteger(ROW_OFFSET) || ROW_OFFSET < 0) throw new Error('ROW_OFFSET must be a non-negative integer');
export const users = SETUP === 'manifest' ? new SharedArray('auction users', () => {
  const lines = open(`${SEED_OUT}/users.csv`).split('\n');
  const head = lines[0].split(',');
  const id = head.indexOf('user_id'), token = head.indexOf('access_token');
  if (id < 0 || token < 0) throw new Error('users.csv must contain user_id and access_token');
  const rows = [];
  for (const line of lines.slice(1)) {
    if (!line.trim()) continue;
    const fields = line.split(',');
    const userId = Number(fields[id]);
    if (!Number.isSafeInteger(userId) || !fields[token]) throw new Error('invalid users.csv row');
    if (userId > ROW_OFFSET && userId < auction.mass_first_user_id) rows.push({ id: userId, token: fields[token].trim() });
  }
  return rows;
}) : [];
if (SETUP === 'manifest' && !users.length) throw new Error('no eligible auction users after ROW_OFFSET');
http.setResponseCallback(http.expectedStatuses(200, 201, 400, 401, 403, 404, 409, 422, 429));

export const outcomes = {
  accepted: new Counter('auction_bid_accepted'), too_low: new Counter('auction_bid_too_low'),
  already_highest: new Counter('auction_bid_already_highest'), closed: new Counter('auction_bid_closed'),
  not_open: new Counter('auction_bid_not_open'), insufficient: new Counter('auction_bid_credit_insufficient'),
  other_client_error: new Counter('auction_bid_other_client_error'), server_error: new Counter('auction_bid_server_error'),
  transport_error: new Counter('auction_bid_transport_error'),
};
export const errorRate = new Rate('auction_error_rate');
export const duration = new Trend('auction_bid_duration', true);
export const initialEnd = new Trend('auction_initial_end_ms');
export const finalEnd = new Trend('auction_final_end_ms');
export const finalizedAt = new Trend('auction_finalized_at_ms');
export const resultAmount = new Trend('auction_result_amount');
export const resultSold = new Counter('auction_result_sold');
export const resultUnsold = new Counter('auction_result_unsold');
export const resultMissing = new Counter('auction_result_missing');
export const summaryTrendStats = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'];

export function get(path, token) {
  return http.get(`${BASE_URL}${path}`, { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'setup/teardown' } });
}
export function checkTarget() {
  const res = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'setup' } });
  if (res.status !== 200) fail(`management ${MANAGEMENT_URL}/actuator/prometheus returned ${res.status}`);
}
export function detail(id, token) {
  const res = get(`/api/auctions/${id}`, token);
  if (res.status !== 200) fail(`GET auction ${id} returned ${res.status}`);
  const d = res.json();
  if (!Number.isFinite(d.minimumBidAmount) || !Number.isFinite(d.minimumIncrementAmount) || !d.endsAt || !d.serverTime) fail(`auction ${id}: invalid detail`);
  return d;
}
export function bid(user, id, amount, tags) {
  const res = http.post(`${BASE_URL}/api/auctions/${id}/bids`, JSON.stringify({ amount }), {
    headers: { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    tags: { name: 'POST /api/auctions/{id}/bids', ...tags },
  });
  let body = {}; try { body = res.json() || {}; } catch (_) { /* non-JSON failure */ }
  const codes = { BID_AMOUNT_TOO_LOW: 'too_low', BID_ALREADY_HIGHEST: 'already_highest', AUCTION_CLOSED: 'closed', AUCTION_NOT_OPEN: 'not_open', CREDIT_INSUFFICIENT: 'insufficient' };
  const outcome = res.status === 0 ? 'transport_error' : res.status === 201 ? 'accepted' : res.status >= 500 ? 'server_error' : codes[body.code] || 'other_client_error';
  const failed = outcome === 'transport_error' || outcome === 'server_error';
  outcomes[outcome].add(1, tags);
  errorRate.add(failed, tags);
  duration.add(res.timings.duration, { outcome, ...tags });
  check(res, { 'no server or transport error': () => !failed });
  return { outcome, body };
}
const metricNames = Object.keys(outcomes).map((key) => `auction_bid_${key === 'accepted' ? 'accepted' : key === 'too_low' ? 'too_low' : key === 'already_highest' ? 'already_highest' : key === 'closed' ? 'closed' : key === 'not_open' ? 'not_open' : key === 'insufficient' ? 'credit_insufficient' : key}`);
export function summarize(data, { headline, conditions, extraLatency = [] }) {
  const count = (key) => data.metrics[key]?.values?.count || 0;
  const trend = (key) => data.metrics[key]?.values || {};
  const failedThresholds = Object.entries(data.metrics).filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok)).map(([name]) => name);
  const outcomeValues = Object.fromEntries([...metricNames, 'dropped_iterations'].map((n) => [n, count(n)]));
  const fmt = (v) => v === undefined ? '-' : `${v.toFixed(1)}ms`;
  const latency = (name, key) => `  ${name.padEnd(20)} med ${fmt(trend(key).med)} p95 ${fmt(trend(key)['p(95)'])} p99 ${fmt(trend(key)['p(99)'])}`;
  const lines = ['', headline, ...Object.entries(outcomeValues).map(([k, v]) => `  ${k.padEnd(35)} ${v}`), latency('bid latency', 'auction_bid_duration'), ...extraLatency.map(([n, k]) => latency(n, k)), `  thresholds ${failedThresholds.length ? `FAILED: ${failedThresholds.join(', ')}` : 'ok'}`, '  next: perf/seed/seed.sh --verify (auction invariants must be 0)', ''];
  const out = { stdout: lines.join('\n') };
  if (SUMMARY_PATH) out[SUMMARY_PATH] = JSON.stringify({ conditions: { baseUrl: BASE_URL, rowOffset: ROW_OFFSET, ...conditions }, outcomes: outcomeValues, failedThresholds, metrics: data.metrics }, null, 2);
  return out;
}

// Self setup runs before load stages. Tokens and credentials never enter logs or summary.
export function loginOrganizer(email, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup' },
  });
  let token; try { token = res.json('accessToken'); } catch (_) { /* rejected or unavailable */ }
  if (res.status !== 200 || !token) fail(`organizer login failed: HTTP ${res.status}`);
  return token;
}
export function loginBidders(first, count, batchSize, password) {
  const started = Date.now(), loggedIn = [];
  for (let start = 0; start < count; start += batchSize) {
    const size = Math.min(batchSize, count - start);
    const requests = Array.from({ length: size }, (_, i) => ({ method: 'POST', url: `${BASE_URL}/api/auth/login`,
      body: JSON.stringify({ email: `fanq${first + start + i}@fanq.com`, password }),
      params: { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup' } } }));
    const responses = http.batch(requests);
    responses.forEach((res, i) => {
      let token; try { token = res.json('accessToken'); } catch (_) { /* failed login */ }
      if (res.status === 200 && token) loggedIn.push({ id: first + start + i, token });
    });
  }
  const seconds = (Date.now() - started) / 1000;
  console.log(`bidder logins: ${loggedIn.length}/${count}, elapsed=${seconds.toFixed(3)}s`);
  if (loggedIn.length / count < 0.99) fail(`bidder login success rate ${loggedIn.length}/${count} is below 99%`);
  return { users: loggedIn, requested: count, succeeded: loggedIn.length, seconds };
}
export function createAuction(token, terms) {
  const res = http.post(`${BASE_URL}/api/auctions`, JSON.stringify(terms), {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, tags: { name: 'setup' },
  });
  let id; try { id = res.json('auctionId'); } catch (_) { /* server response failed */ }
  if (res.status !== 201 || !Number.isSafeInteger(id) || id < 1) fail(`auction creation failed: HTTP ${res.status}`);
  return id;
}
