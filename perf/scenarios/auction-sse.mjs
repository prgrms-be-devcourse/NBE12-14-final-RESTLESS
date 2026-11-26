#!/usr/bin/env node
// AU-5: hold SSE connections and measure event delivery after a bid. Node 24, no packages.
// MODE (#448):
//   steps   (default, AU-5) connection steps with one measurement bid per step. 503 AUCTION_STREAM_BUSY above the
//           server limit is counted as an expected rejection, not a failure.
//   cleanup (AU-6) open STEPS, then drop every client without a bid and time how long the server gauge takes to drain.
//   soak    (AU-8) open STEPS and hold HOLD_SECONDS without bids; record heartbeats and drops per minute.
//   mixed   (AU-7) watch an existing auction (AUCTION_ID, e.g. the one auction-burst.js created) while another
//           process bids. No measurement bid; record events per connection, gaps, and whether every watcher caught up.
import { writeFile, mkdir } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { randomUUID } from 'node:crypto';
import { isIP } from 'node:net';

const env = process.env;
const APP_IP = env.PERF_APP_IP;
const APP_HOST = env.PERF_APP_HOST || 'perf-local';
const URL_IP = APP_IP?.includes(':') ? `[${APP_IP}]` : APP_IP;
const BASE_URL = URL_IP ? `http://${URL_IP}:8080` : '';
const MANAGEMENT_URL = URL_IP ? `http://${URL_IP}:8081` : '';
const allowed = (env.PERF_ALLOWED_APP_HOSTS || '').split(',').map((v) => v.trim()).filter(Boolean);
const SUMMARY_PATH = resolve(env.SUMMARY_PATH || '.ai-output/perf/auction/sse.json');
const FIRST_ID = Number(env.BIDDER_FIRST_ID || 1);
const LAST_ALLOWED_ID = Number(env.BIDDER_LAST_ALLOWED_ID || 96900);
const ACCOUNTS = Number(env.ACCOUNTS || 1000);
const LOGIN_RATE = Number(env.LOGIN_RATE || 15);
// 관전·측정 계정 토큰은 기본으로 시드 users.csv에서 읽는다 (#483). AUTH_MODE=login이면 LOGIN_RATE로 로그인한다.
const AUTH_MODE = env.AUTH_MODE || 'seed';
const SEED_OUT = resolve(env.SEED_OUT || resolve(dirname(fileURLToPath(import.meta.url)), '../seed/out'));
const CONNECT_RATE = Number(env.CONNECT_RATE || 200);
const HOLD_SECONDS = Number(env.HOLD_SECONDS || 60);
const PROPAGATION_WAIT_SECONDS = Number(env.PROPAGATION_WAIT_SECONDS || 15);
const CONNECT_WAIT_SECONDS = Number(env.CONNECT_WAIT_SECONDS || 30);
const POST_RUN_CLOSE_WAIT_SECONDS = Number(env.POST_RUN_CLOSE_WAIT_SECONDS || 90);
const STEPS = (env.STEPS || '2000,4000,6000,8000,10000').split(',').map((v) => Number(v.trim()));
const MODE = env.MODE || 'steps';
const EXISTING_AUCTION_ID = env.AUCTION_ID ? Number(env.AUCTION_ID) : null;
const DRAIN_WAIT_SECONDS = Number(env.DRAIN_WAIT_SECONDS || 60);
const SNAPSHOT_SECONDS = Number(env.SNAPSHOT_SECONDS || 60);
const MEASURE_BID = MODE === 'steps';
const ORGANIZER_EMAIL = env.ORGANIZER_EMAIL || 'organizer1@fanq.com';
const PASSWORD = env.SEED_PASSWORD || '0000';
const EXTENSION_SECONDS = 60;
// The current highest bidder cannot bid again, so two measurement accounts alternate.
const REQUESTED_BIDDERS = ACCOUNTS + 2;

if (!['steps', 'cleanup', 'soak', 'mixed'].includes(MODE)) throw new Error('MODE must be steps, cleanup, soak, or mixed');
if (!['seed', 'login'].includes(AUTH_MODE)) throw new Error('AUTH_MODE must be seed or login');
if (MODE === 'mixed' && !Number.isSafeInteger(EXISTING_AUCTION_ID)) throw new Error('MODE=mixed requires AUCTION_ID');
if (MODE !== 'mixed' && EXISTING_AUCTION_ID !== null) throw new Error('AUCTION_ID is only used with MODE=mixed');
if (![DRAIN_WAIT_SECONDS, SNAPSHOT_SECONDS].every((n) => Number.isSafeInteger(n) && n > 0)) throw new Error('invalid DRAIN_WAIT_SECONDS or SNAPSHOT_SECONDS');
if (!APP_IP || !isIP(APP_IP) || !BASE_URL || !MANAGEMENT_URL) throw new Error('PERF_APP_IP must be an IP address (target IP is never stored in the repository)');
if (!/^perf-[a-z0-9][a-z0-9.-]*$/i.test(APP_HOST) || /prod/i.test(APP_HOST) || !allowed.includes(APP_HOST)) {
  throw new Error(`host ${APP_HOST} is not an allowed perf host`);
}
for (const url of [BASE_URL, MANAGEMENT_URL]) {
  if (!/^https?:\/\//.test(url) || /prod/i.test(url)) throw new Error('invalid perf service URL');
}
if (![ACCOUNTS, LOGIN_RATE, CONNECT_RATE, HOLD_SECONDS, PROPAGATION_WAIT_SECONDS, CONNECT_WAIT_SECONDS, POST_RUN_CLOSE_WAIT_SECONDS]
  .every((n) => Number.isSafeInteger(n) && n > 0) || FIRST_ID < 1 || FIRST_ID + REQUESTED_BIDDERS - 1 > LAST_ALLOWED_ID
  || LAST_ALLOWED_ID > 96900 || STEPS.length < 1 || STEPS.some((n) => !Number.isSafeInteger(n) || n <= 0)
  || new Set(STEPS).size !== STEPS.length || STEPS.some((n, i) => i > 0 && n <= STEPS[i - 1])) {
  throw new Error('invalid account range, rate, or STEPS; bidder IDs must end at or below 96900');
}

const headers = (extra = {}, port = '8080') => ({ Host: `${APP_HOST}:${port}`, ...extra });
const results = [];
const connections = [];
const pendingEvents = new Map();
const metrics = { organizerLoginMs: null, observerLoginRequested: AUTH_MODE === 'login' ? ACCOUNTS : 0, observerLoginSucceeded: 0,
  observerLoginSeconds: null, measurementBidderIds: [FIRST_ID + ACCOUNTS, FIRST_ID + ACCOUNTS + 1],
  createdAuctionId: null, steps: results, baselineServerMetric: null, activeServerMetric: null,
  serverMetricAfterClose: null, cleanupProbe: null, auctionLifetimeSeconds: null, closedEventCount: 0,
  drain: null, soakSnapshots: [], mixed: null, rejectedMetricBefore: null, rejectedMetricAfter: null,
  heartbeatRemovedBefore: null, heartbeatRemovedAfter: null };
let measurementTokens = [];
let stopping = false;
let finished = false;
let serverOffsetMs = 0;

async function request(url, options = {}, port = '8080') {
  return fetch(url, { ...options, headers: headers(options.headers || {}, port), redirect: 'manual' });
}
async function login(email) {
  const response = await request(`${BASE_URL}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }), signal: AbortSignal.timeout(15000),
  });
  let body = {};
  try { body = await response.json(); } catch { /* invalid response */ }
  if (response.status !== 200 || !body.accessToken) throw new Error(`login failed for account ${email.replace(/@.*/, '')}: HTTP ${response.status}`);
  return body.accessToken;
}
/** users.csv에서 fanq{first}~fanq{first+count-1}의 Access Token을 순서대로 읽는다. 빠지면 멈춘다. */
function loadSeedTokens(first, count) {
  const lines = readFileSync(`${SEED_OUT}/users.csv`, 'utf8').split('\n');
  const header = lines[0].split(',');
  const emailAt = header.indexOf('email'), tokenAt = header.indexOf('access_token');
  if (emailAt < 0 || tokenAt < 0) throw new Error('users.csv needs email and access_token columns');
  const tokens = new Array(count);
  for (const line of lines.slice(1)) {
    if (!line) continue;
    const cols = line.split(',');
    const match = /^fanq(\d+)@fanq\.com$/.exec(cols[emailAt]);
    const offset = match ? Number(match[1]) - first : -1;
    if (offset >= 0 && offset < count) tokens[offset] = cols[tokenAt];
  }
  const missing = tokens.findIndex((token) => !token);
  if (missing >= 0) throw new Error(`users.csv has no token for fanq${first + missing}@fanq.com; check SEED_OUT and the seed scale`);
  return tokens;
}
/** 첫·마지막 토큰의 exp가 needUntilMs 전이면 멈춘다. 서명은 확인하지 않는다. */
function requireTokensValidUntil(tokens, needUntilMs) {
  for (const token of [tokens[0], tokens.at(-1)]) {
    let exp = NaN;
    try { exp = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')).exp * 1000; } catch { /* unreadable */ }
    if (!Number.isFinite(exp)) throw new Error('seed token has no readable exp; refetch perf/seed/out from the perf server');
    if (exp < needUntilMs) {
      throw new Error(`seed tokens expire at ${new Date(exp).toISOString()}, before this run ends; run perf-ec2.sh refresh <scale> and fetch the bundle again`);
    }
  }
}
async function getDetail(id, token = '') {
  const response = await request(`${BASE_URL}/api/auctions/${id}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}, signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error(`GET auction ${id} returned HTTP ${response.status}`);
  return response.json();
}
async function waitUntilOpen(id, startsAtMs) {
  const deadline = startsAtMs + 60000;
  while (!stopping) {
    const d = await getDetail(id);
    if (d.status === 'OPEN') return d;
    if (Date.parse(d.serverTime) > deadline) throw new Error(`auction ${id} did not open; check the auction scheduler`);
    await delay(1000);
  }
  throw new Error('interrupted while waiting for auction to open');
}
async function checkTarget() {
  const response = await request(`${MANAGEMENT_URL}/actuator/prometheus`, { signal: AbortSignal.timeout(5000) }, '8081');
  if (response.status !== 200) throw new Error(`management /actuator/prometheus returned HTTP ${response.status}`);
}
function delay(ms) { return new Promise((resolveDelay) => setTimeout(resolveDelay, ms)); }
function safeMetricValue(text, name) {
  const vals = [];
  for (const line of text.split('\n')) {
    if (line.startsWith('#')) continue;
    const split = line.trim().split(/\s+/);
    const metric = split[0].split('{')[0];
    if (metric === name) vals.push(Number(split.at(-1)));
  }
  return vals.length ? vals.reduce((a, b) => a + b, 0) : null;
}
async function readMetric(name) {
  try {
    const response = await request(`${MANAGEMENT_URL}/actuator/prometheus`, { signal: AbortSignal.timeout(5000) }, '8081');
    if (!response.ok) return null;
    const body = await response.text();
    return safeMetricValue(body, name);
  } catch { return null; }
}
const readStreamMetric = () => readMetric('auction_stream_connections');
async function createAuction(token, startsAt, endsAt) {
  const response = await request(`${BASE_URL}/api/auctions`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: `perf sse ${new Date().toISOString()}`, itemName: 'Performance test item',
      itemDescription: 'SSE connection measurement', startingPriceAmount: 10000, minimumIncrementAmount: 100,
      extensionDurationSeconds: EXTENSION_SECONDS, startsAt: startsAt.toISOString(), endsAt: endsAt.toISOString() }),
    signal: AbortSignal.timeout(10000),
  });
  let body = {};
  try { body = await response.json(); } catch { /* report status below */ }
  if (response.status !== 201 || !Number.isSafeInteger(body.auctionId)) throw new Error(`auction creation failed: HTTP ${response.status}`);
  return body.auctionId;
}

function parseFrames(buffer) {
  const frames = [];
  let splitAt;
  while ((splitAt = buffer.search(/\r?\n\r?\n/)) >= 0) {
    const frame = buffer.slice(0, splitAt);
    const separator = buffer.slice(splitAt).match(/^\r?\n\r?\n/)[0];
    buffer = buffer.slice(splitAt + separator.length);
    const fields = { data: [] };
    for (const line of frame.split(/\r?\n/)) {
      const colon = line.indexOf(':');
      const key = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (line.startsWith(':')) fields.comment = true;
      else if (key === 'data') fields.data.push(value);
      else if (key === 'id') fields.id = value;
      else if (key === 'event') fields.event = value;
    }
    if (fields.data.length || fields.comment) frames.push(fields);
  }
  return { frames, remainder: buffer };
}

function notifyRevision(revision, arrivedAt, connectionId) {
  for (const pending of pendingEvents.values()) {
    if (pending.connectionId === connectionId && revision >= pending.revision && !pending.seen.has(connectionId)) {
      pending.delays.push(arrivedAt - pending.receivedAt);
      pending.seen.add(connectionId);
    }
  }
}
async function readConnection(connection, response) {
  let buffer = '';
  try {
    const decoder = new TextDecoder();
    for await (const chunk of response.body) {
      const parsed = parseFrames(buffer + decoder.decode(chunk, { stream: true }));
      buffer = parsed.remainder;
      for (const frame of parsed.frames) {
        if (!frame.data.length) {
          // Heartbeat comment (#447). Track how regularly it arrives.
          const now = performance.now();
          if (connection.lastHeartbeatAt) connection.maxHeartbeatGapMs = Math.max(connection.maxHeartbeatGapMs, now - connection.lastHeartbeatAt);
          connection.lastHeartbeatAt = now;
          connection.heartbeats++;
          continue;
        }
        let data;
        try { data = JSON.parse(frame.data.join('\n')); } catch { continue; }
        const revision = Number(data.revision ?? frame.id);
        if (Number.isFinite(revision)) {
          // Server-clock time when this watcher first saw a newer revision (AU-7 final delivery lag).
          if (revision > connection.revision) connection.revisionServerAt = Date.now() + serverOffsetMs;
          connection.revision = Math.max(connection.revision, revision);
          const now = performance.now();
          connection.events++;
          connection.lastRevisionAt = now;
          if (!connection.firstEventAt) connection.firstEventAt = now;
          notifyRevision(revision, now, connection.index);
        }
        if (data.status === 'CLOSED') connection.closedEvent = true;
      }
    }
  } catch (error) {
    if (!connection.controller.signal.aborted) connection.error = error?.name || 'stream_error';
  } finally {
    if (connection.active) {
      connection.active = false;
      if (!connection.closedEvent && !connection.controller.signal.aborted) connection.disconnected = true;
    }
  }
}

async function openConnection(index, token) {
  const connection = { index, active: false, firstEventAt: null, revision: -1, disconnected: false,
    closedEvent: false, error: null, controller: new AbortController(), heartbeats: 0, maxHeartbeatGapMs: 0,
    events: 0, revisionServerAt: null, busy: null };
  connections[index] = connection;
  try {
    const response = await request(`${BASE_URL}/api/auctions/${metrics.createdAuctionId}/stream`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      signal: connection.controller.signal,
    });
    connection.status = response.status;
    if (response.status === 503) {
      // Expected above the server-wide limit (#244). Keep the code and Retry-After to check the contract.
      let body = {};
      try { body = await response.json(); } catch { /* report status */ }
      connection.busy = { code: body.code || null, retryAfter: response.headers.get('retry-after') };
    }
    if (response.status !== 200 || !response.body) {
      connection.error = `http_${response.status}`;
      connection.controller.abort();
      return connection;
    }
    connection.active = true;
    connection.readTask = readConnection(connection, response);
    return connection;
  } catch (error) {
    connection.status = 0;
    connection.error = error?.name || 'connection_error';
    return connection;
  }
}

function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)];
}
async function writeSummary(interrupted = false) {
  if (finished) return;
  finished = true;
  const statusCounts = connections.reduce((acc, c) => {
    if (!c) return acc;
    const key = String(c.status || 0);
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  const correctnessWarnings = [];
  const isBusy = (c) => c?.busy?.code === 'AUCTION_STREAM_BUSY';
  if (connections.some((c) => c?.busy && !isBusy(c))) correctnessWarnings.push('503 without AUCTION_STREAM_BUSY');
  if (connections.some((c) => isBusy(c) && !/^[1-9][0-9]*$/.test(c.busy.retryAfter || ''))) correctnessWarnings.push('AUCTION_STREAM_BUSY without a positive Retry-After');
  if (connections.some((c) => !c?.firstEventAt && !isBusy(c))) correctnessWarnings.push('one or more SSE connections failed before the initial event');
  if (connections.some((c) => c?.disconnected)) correctnessWarnings.push('one or more SSE connections disconnected during the run');
  if (MEASURE_BID && results.some((step) => step.bidOutcome?.status !== 201)) correctnessWarnings.push('measurement bid did not receive 201');
  if (MODE === 'steps' && !interrupted && connections.length && metrics.closedEventCount < connections.filter((c) => c?.status === 200).length) {
    correctnessWarnings.push('some successful SSE connections did not receive the final CLOSED event');
  }
  if (metrics.cleanupProbe && metrics.cleanupProbe.status !== 201) correctnessWarnings.push('final SSE cleanup probe bid was not accepted');
  if (MODE === 'steps' && connections.length && !interrupted
      && metrics.closedEventCount < connections.filter((c) => c?.status === 200).length
      && !metrics.cleanupProbe) correctnessWarnings.push('final SSE cleanup probe was not recorded');
  if (metrics.baselineServerMetric !== null && metrics.serverMetricAfterClose !== null
      && metrics.serverMetricAfterClose > metrics.baselineServerMetric) {
    correctnessWarnings.push('server still reports SSE connections after client shutdown');
  }
  const okConnections = connections.filter((c) => c?.status === 200);
  const heartbeats = okConnections.map((c) => c.heartbeats);
  const summary = { target: { steps: 'AU-5 SSE', cleanup: 'AU-6 SSE cleanup', soak: 'AU-8 SSE soak', mixed: 'AU-7 SSE mixed' }[MODE],
    mode: MODE, host: APP_HOST, interrupted, conditions: {
    steps: STEPS, drainWaitSeconds: DRAIN_WAIT_SECONDS, snapshotSeconds: SNAPSHOT_SECONDS, connectRate: CONNECT_RATE, holdSeconds: HOLD_SECONDS, accounts: ACCOUNTS,
    observerIds: { first: FIRST_ID, last: FIRST_ID + ACCOUNTS - 1 },
    measurementBidderIds: metrics.measurementBidderIds, createdAuctionId: metrics.createdAuctionId,
    organizer: ORGANIZER_EMAIL, authMode: AUTH_MODE, loginRate: AUTH_MODE === 'login' ? LOGIN_RATE : null, propagationWaitSeconds: PROPAGATION_WAIT_SECONDS,
    auctionLifetimeSeconds: metrics.auctionLifetimeSeconds, postRunCloseWaitSeconds: POST_RUN_CLOSE_WAIT_SECONDS,
  }, preparation: { organizerLoginMs: metrics.organizerLoginMs, observerLoginRequested: metrics.observerLoginRequested,
    observerLoginSucceeded: metrics.observerLoginSucceeded, observerLoginSeconds: metrics.observerLoginSeconds },
  steps: results, totals: { attemptedConnections: connections.length,
    receivedResponses: connections.filter((c) => c?.status > 0).length,
    connectionFailures: connections.filter((c) => !c?.status).length,
    fiveXx: connections.filter((c) => c?.status >= 500 && !isBusy(c)).length, statusCounts,
    rejectedAtLimit: connections.filter(isBusy).length,
    retryAfterValues: [...new Set(connections.filter(isBusy).map((c) => c.busy.retryAfter))] },
  heartbeat: { connections: heartbeats.length, perConnectionMin: heartbeats.length ? Math.min(...heartbeats) : null,
    perConnectionP50: percentile(heartbeats, 0.5),
    maxGapMsP95: percentile(okConnections.filter((c) => c.maxHeartbeatGapMs).map((c) => c.maxHeartbeatGapMs), 0.95),
    maxGapMs: okConnections.length ? Math.max(0, ...okConnections.map((c) => c.maxHeartbeatGapMs)) : null },
  serverRejectedMetric: { before: metrics.rejectedMetricBefore, after: metrics.rejectedMetricAfter },
  serverHeartbeatRemovedMetric: { before: metrics.heartbeatRemovedBefore, after: metrics.heartbeatRemovedAfter },
  drain: metrics.drain, soakSnapshots: metrics.soakSnapshots, mixed: metrics.mixed,
  correctnessWarnings, activeConnectionsAtStop: connections.filter((c) => c?.active).length,
  failedConnections: connections.filter((c) => c && !c.firstEventAt && !isBusy(c)).map((c) => ({ status: c.status || 0, error: c.error || 'no_initial_event' })),
  disconnectedWhileHeld: connections.filter((c) => c?.disconnected).length,
  closedEventConnections: connections.filter((c) => c?.closedEvent).length,
  serverAuctionStreamConnections: metrics.activeServerMetric,
  serverAuctionStreamConnectionsBefore: metrics.baselineServerMetric,
  serverAuctionStreamConnectionsAfterClose: metrics.serverMetricAfterClose,
  cleanupProbe: metrics.cleanupProbe };
  await mkdir(dirname(SUMMARY_PATH), { recursive: true });
  await writeFile(SUMMARY_PATH, `${JSON.stringify(summary, null, 2)}\n`);
  console.log(`SSE summary: ${SUMMARY_PATH}`);
  for (const step of results) console.log(`step ${step.connections}: attempted=${step.attempted} success=${step.success} rejectedAtLimit=${step.rejectedAtLimit} failed=${step.failed} disconnected=${step.disconnected} priceChanged=${step.priceChanged}/${step.openConnections} delay p50=${step.deliveryDelayMs.p50 ?? '-'} p95=${step.deliveryDelayMs.p95 ?? '-'} max=${step.deliveryDelayMs.max ?? '-'}`);
  if (correctnessWarnings.length) console.error(`!! 정확성 확인 필요: ${correctnessWarnings.join('; ')}`);
  if (interrupted) process.exitCode = 130;
}
/** Drop every client and time how long the server gauge takes to return to the baseline (heartbeat cleanup). */
async function measureDrain() {
  const startedAt = performance.now();
  for (const connection of connections) connection?.controller.abort();
  const timeline = [];
  let drainedAfterSeconds = null;
  while (performance.now() - startedAt < DRAIN_WAIT_SECONDS * 1000) {
    const value = await readStreamMetric();
    const t = (performance.now() - startedAt) / 1000;
    timeline.push({ t: Number(t.toFixed(1)), gauge: value });
    if (value !== null && value <= (metrics.baselineServerMetric ?? 0)) { drainedAfterSeconds = Number(t.toFixed(1)); break; }
    await delay(1000);
  }
  metrics.drain = { startedAt: new Date(Date.now() - (performance.now() - startedAt)).toISOString(), clientsDropped: connections.filter((c) => c?.status === 200).length, drainedAfterSeconds, timeline };
  metrics.serverMetricAfterClose = timeline.at(-1)?.gauge ?? null;
  console.log(`drain: ${metrics.drain.clientsDropped} clients dropped, gauge back to baseline after ${drainedAfterSeconds ?? `>${DRAIN_WAIT_SECONDS}`}s`);
}

async function finish(interrupted = false) {
  stopping = true;
  if (!interrupted && MODE !== 'steps') {
    if (MODE === 'mixed') await recordMixed();
    await measureDrain();
    await Promise.allSettled(connections.map((connection) => connection?.readTask).filter(Boolean));
    metrics.rejectedMetricAfter = await readMetric('auction_stream_rejected_total');
    metrics.heartbeatRemovedAfter = await readMetric('auction_stream_heartbeat_removed_total');
    await writeSummary(false);
    return;
  }
  if (!interrupted) {
    const closeDeadline = performance.now() + POST_RUN_CLOSE_WAIT_SECONDS * 1000;
    while (connections.some((connection) => connection?.active && !connection.closedEvent)
      && performance.now() < closeDeadline) await delay(100);
  }
  for (const connection of connections) connection?.controller.abort();
  await Promise.allSettled(connections.map((connection) => connection?.readTask).filter(Boolean));
  if (!interrupted) {
    metrics.closedEventCount = connections.filter((connection) => connection?.closedEvent).length;
    if (metrics.createdAuctionId && measurementTokens.length && metrics.closedEventCount < connections.filter((connection) => connection?.status === 200).length) {
      try {
        const d = await getDetail(metrics.createdAuctionId);
        if (d.status === 'OPEN') {
          const bidderIndex = results.length % measurementTokens.length;
          const response = await request(`${BASE_URL}/api/auctions/${metrics.createdAuctionId}/bids`, {
            method: 'POST', headers: { Authorization: `Bearer ${measurementTokens[bidderIndex]}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
            body: JSON.stringify({ amount: d.minimumBidAmount }), signal: AbortSignal.timeout(Number(env.REQUEST_TIMEOUT_SECONDS || 60) * 1000),
          });
          metrics.cleanupProbe = { status: response.status };
        }
      } catch (error) { metrics.cleanupProbe = { status: 0, error: error?.name || 'connection_error' }; }
    }
    // Without a bid the server only notices dropped clients through heartbeats (#447), so wait past two intervals.
    const gaugeDeadline = performance.now() + DRAIN_WAIT_SECONDS * 1000;
    while (performance.now() < gaugeDeadline) {
      metrics.serverMetricAfterClose = await readStreamMetric();
      if (metrics.serverMetricAfterClose !== null
          && (metrics.baselineServerMetric === null || metrics.serverMetricAfterClose <= metrics.baselineServerMetric)) break;
      await delay(200);
    }
    metrics.rejectedMetricAfter = await readMetric('auction_stream_rejected_total');
    metrics.heartbeatRemovedAfter = await readMetric('auction_stream_heartbeat_removed_total');
  }
  await writeSummary(interrupted);
}
process.once('SIGINT', () => { void finish(true).finally(() => process.exit()); });
process.once('SIGTERM', () => { void finish(true).finally(() => process.exit()); });

async function main() {
  await checkTarget();
  const organizerStarted = performance.now();
  const organizerToken = await login(ORGANIZER_EMAIL);
  metrics.organizerLoginMs = performance.now() - organizerStarted;
  const before = performance.now();
  const clock = await getDetail(1, organizerToken);
  const after = performance.now();
  serverOffsetMs = Date.parse(clock.serverTime) - (Date.now() - (after - before) / 2);
  if (!Number.isFinite(serverOffsetMs)) throw new Error('could not determine server clock offset from auction detail.serverTime');
  const nowServer = Date.now() + serverOffsetMs;
  const startsAtMs = nowServer + 20000;
  const plannedStageSeconds = STEPS.reduce((total, requested, index) => {
    const previous = index ? STEPS[index - 1] : 0;
    return total + Math.max(HOLD_SECONDS, PROPAGATION_WAIT_SECONDS) + (requested - previous) / CONNECT_RATE;
  }, 0);
  const loginSeconds = AUTH_MODE === 'login' ? REQUESTED_BIDDERS / LOGIN_RATE : 0;
  metrics.auctionLifetimeSeconds = Math.ceil(20 + loginSeconds + plannedStageSeconds + EXTENSION_SECONDS);
  const seedTokens = AUTH_MODE === 'seed' ? loadSeedTokens(FIRST_ID, REQUESTED_BIDDERS) : null;
  // soak·mixed는 경매 수명과 따로 HOLD_SECONDS만큼 더 유지한다.
  if (seedTokens) requireTokensValidUntil(seedTokens, Date.now() + (metrics.auctionLifetimeSeconds + HOLD_SECONDS + POST_RUN_CLOSE_WAIT_SECONDS + 600) * 1000);
  let opened;
  if (MODE === 'mixed') {
    metrics.createdAuctionId = EXISTING_AUCTION_ID;
    opened = await getDetail(EXISTING_AUCTION_ID);
    if (opened.status !== 'OPEN') throw new Error(`auction ${EXISTING_AUCTION_ID} is ${opened.status}, not OPEN`);
    console.log(`watching existing auction ${EXISTING_AUCTION_ID} at revision ${opened.revision}; server clock offset ${serverOffsetMs.toFixed(1)}ms`);
  } else {
    const endsAtMs = nowServer + metrics.auctionLifetimeSeconds * 1000;
    metrics.createdAuctionId = await createAuction(organizerToken, new Date(startsAtMs), new Date(endsAtMs));
    opened = await waitUntilOpen(metrics.createdAuctionId, startsAtMs);
    console.log(`created auction ${metrics.createdAuctionId}; server clock offset ${serverOffsetMs.toFixed(1)}ms; OPEN`);
  }
  metrics.baselineServerMetric = await readStreamMetric();
  metrics.rejectedMetricBefore = await readMetric('auction_stream_rejected_total');
  metrics.heartbeatRemovedBefore = await readMetric('auction_stream_heartbeat_removed_total');

  const loginStartedAt = performance.now();
  const accountTokens = seedTokens ? seedTokens.slice(0, ACCOUNTS) : [];
  const loginEpoch = performance.now() + 1000;
  for (let i = 0; !seedTokens && i < ACCOUNTS; i++) {
    const scheduled = loginEpoch + i * 1000 / LOGIN_RATE;
    if (performance.now() < scheduled) await delay(scheduled - performance.now());
    accountTokens.push(await login(`fanq${FIRST_ID + i}@fanq.com`));
    metrics.observerLoginSucceeded++;
  }
  const observerLoginSeconds = (performance.now() - loginStartedAt) / 1000;
  metrics.observerLoginSeconds = observerLoginSeconds;
  measurementTokens = seedTokens && MEASURE_BID ? seedTokens.slice(ACCOUNTS, ACCOUNTS + 2) : [];
  for (const id of !seedTokens && MEASURE_BID ? metrics.measurementBidderIds : []) {
    await delay(1000 / LOGIN_RATE);
    measurementTokens.push(await login(`fanq${id}@fanq.com`));
  }
  console.log(seedTokens
    ? `using seed tokens for ${ACCOUNTS} observers${MEASURE_BID ? ' and two measurement bidders' : ''} (${SEED_OUT}/users.csv)`
    : `logged in ${metrics.observerLoginSucceeded}/${ACCOUNTS} observers in ${observerLoginSeconds.toFixed(2)}s at ${LOGIN_RATE}/s${MEASURE_BID ? '; two measurement bidders will alternate' : ''}`);

  let previous = 0;
  let currentRevision = opened.revision;
  for (const requested of STEPS) {
    if (stopping) break;
    const stageStart = performance.now();
    // Wall-clock UTC so the 2s actuator samples (heap, direct buffer) can be matched to each step.
    const step = { startedAt: new Date().toISOString(), connectedAt: null, endedAt: null,
      connections: requested, attempted: requested, added: requested - previous, success: 0, rejectedAtLimit: 0, failed: 0,
      disconnected: 0, priceChanged: 0, openConnections: 0, deliveryDelayMs: { p50: null, p95: null, max: null },
      statusErrors: {}, serverMetric: null };
    for (let index = previous; index < requested; index++) {
      const due = stageStart + (index - previous) * 1000 / CONNECT_RATE;
      if (performance.now() < due) await delay(due - performance.now());
      openConnection(index, accountTokens[index % ACCOUNTS]);
    }
    const deadline = performance.now() + CONNECT_WAIT_SECONDS * 1000;
    while (connections.slice(0, requested).filter((c) => c?.firstEventAt || (c && !c.active && c.error)).length < requested
      && performance.now() < deadline && !stopping) await delay(100);
    const stageConnections = connections.slice(0, requested);
    step.connectedAt = new Date().toISOString();
    step.success = stageConnections.filter((c) => c?.status === 200 && c.firstEventAt !== null).length;
    step.rejectedAtLimit = stageConnections.filter((c) => c?.busy?.code === 'AUCTION_STREAM_BUSY').length;
    step.retryAfter = [...new Set(stageConnections.filter((c) => c?.busy).map((c) => c.busy.retryAfter))];
    step.failed = requested - step.success - step.rejectedAtLimit;
    step.statusErrors = stageConnections.reduce((acc, c) => {
      if ((!c || !c.firstEventAt) && c?.busy?.code !== 'AUCTION_STREAM_BUSY') { const key = c?.status ? `HTTP_${c.status}` : (c?.error || 'connect_timeout'); acc[key] = (acc[key] || 0) + 1; }
      return acc;
    }, {});
    step.openConnections = stageConnections.filter((c) => c?.active).length;
    step.disconnected = stageConnections.filter((c) => c?.disconnected).length;
    step.serverMetric = await readStreamMetric();
    metrics.activeServerMetric = step.serverMetric;
    if (!MEASURE_BID) {
      step.endedAt = new Date().toISOString();
      results.push(step);
      console.log(`step ${requested}: attempted=${step.attempted}, success=${step.success}, rejectedAtLimit=${step.rejectedAtLimit}, failed=${step.failed}, active=${step.openConnections}, streamMetric=${step.serverMetric ?? '-'}`);
      previous = requested;
      continue;
    }

    const bidResponseStart = performance.now();
    const measurementToken = measurementTokens[results.length % measurementTokens.length];
    let bidResponse;
    try {
      bidResponse = await request(`${BASE_URL}/api/auctions/${metrics.createdAuctionId}/bids`, {
        method: 'POST', headers: { Authorization: `Bearer ${measurementToken}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
        body: JSON.stringify({ amount: opened.minimumBidAmount + opened.minimumIncrementAmount * (results.length + 1) }),
        signal: AbortSignal.timeout(Number(env.REQUEST_TIMEOUT_SECONDS || 60) * 1000),
      });
    } catch (error) {
      // When watchers fill the server's connection limit, even an ordinary API request cannot connect.
      // Record that as this step's result instead of aborting the whole run.
      bidResponse = null;
      step.bidOutcome = { status: 0, error: error?.cause?.code || error?.name || 'connection_error' };
    }
    const bidReceivedAt = performance.now();
    let bidBody = {};
    if (bidResponse) { try { bidBody = await bidResponse.json(); } catch { /* report status */ } }
    if (!bidResponse) {
      step.bidResponseMs = bidReceivedAt - bidResponseStart;
    } else if (bidResponse.status !== 201) {
      step.bidOutcome = { status: bidResponse.status, code: bidBody.code || null };
    } else {
      step.bidOutcome = { status: bidResponse.status };
      // Bid responses omit revision; each accepted bid increments the known revision by one.
      currentRevision++;
      const wantedRevision = currentRevision;
      // Populate one pending record per connection so distinct observers can be counted.
      const perConnection = stageConnections.filter((c) => c?.active).map((c) => {
        const eventWait = { revision: wantedRevision, receivedAt: bidReceivedAt, delays: [], seen: new Set(), connectionId: c.index };
        const key = `${results.length}:${c.index}`;
        pendingEvents.set(key, eventWait);
        if (c.revision >= wantedRevision && c.lastRevisionAt !== undefined) {
          eventWait.delays.push(c.lastRevisionAt - bidReceivedAt);
          eventWait.seen.add(c.index);
        }
        return { key, eventWait };
      });
      step.bidAcceptedAt = bidBody.acceptedAt;
      step.bidAmount = bidBody.amount;
      step.bidResponseMs = performance.now() - bidResponseStart;
      step.targetRevision = wantedRevision;
      await delay(PROPAGATION_WAIT_SECONDS * 1000);
      const delivery = perConnection.filter((entry) => entry.eventWait.delays.length).map((entry) => entry.eventWait.delays[0]);
      for (const entry of perConnection) pendingEvents.delete(entry.key);
      step.priceChanged = delivery.length;
      step.deliveryDelayMs = { p50: percentile(delivery, 0.5), p95: percentile(delivery, 0.95), max: delivery.length ? Math.max(...delivery) : null };
    }
    const elapsed = (performance.now() - stageStart) / 1000;
    if (elapsed < HOLD_SECONDS) await delay((HOLD_SECONDS - elapsed) * 1000);
    step.disconnected = stageConnections.filter((c) => c?.disconnected).length;
    step.openConnections = stageConnections.filter((c) => c?.active).length;
    step.serverMetric = await readStreamMetric();
    metrics.activeServerMetric = step.serverMetric;
    step.endedAt = new Date().toISOString();
    results.push(step);
    console.log(`step ${requested}: attempted=${step.attempted}, success=${step.success}, rejectedAtLimit=${step.rejectedAtLimit}, failed=${step.failed}, active=${step.openConnections}, streamMetric=${step.serverMetric ?? '-'}, changed=${step.priceChanged}, delay_p95=${step.deliveryDelayMs.p95 ?? '-'}`);
    previous = requested;
  }
  if (MODE === 'soak' || MODE === 'mixed') await holdAndSnapshot();
}

/** Hold the opened connections for HOLD_SECONDS and record a snapshot every SNAPSHOT_SECONDS. */
async function holdAndSnapshot() {
  const startedAt = performance.now();
  console.log(`holding ${connections.filter((c) => c?.active).length} connections for ${HOLD_SECONDS}s`);
  while (!stopping && performance.now() - startedAt < HOLD_SECONDS * 1000) {
    await delay(Math.min(SNAPSHOT_SECONDS * 1000, HOLD_SECONDS * 1000 - (performance.now() - startedAt)));
    const ok = connections.filter((c) => c?.status === 200);
    const snapshot = { at: new Date().toISOString(), t: Math.round((performance.now() - startedAt) / 1000), active: ok.filter((c) => c.active).length,
      disconnected: ok.filter((c) => c.disconnected).length, serverMetric: await readStreamMetric(),
      heartbeatsP50: percentile(ok.map((c) => c.heartbeats), 0.5), eventsP50: percentile(ok.map((c) => c.events), 0.5) };
    metrics.soakSnapshots.push(snapshot);
    console.log(`hold t=${snapshot.t}s active=${snapshot.active} disconnected=${snapshot.disconnected} streamMetric=${snapshot.serverMetric ?? '-'} heartbeatsP50=${snapshot.heartbeatsP50 ?? '-'} eventsP50=${snapshot.eventsP50 ?? '-'}`);
  }
}

/** After the bidding process: did every watcher reach the server's latest revision, and how bursty was delivery? */
async function recordMixed() {
  const ok = connections.filter((c) => c?.status === 200);
  let final = null;
  try { final = await getDetail(metrics.createdAuctionId); } catch { /* reported as null */ }
  await delay(PROPAGATION_WAIT_SECONDS * 1000);
  const finalRevision = final?.revision ?? null;
  const lastAcceptedAt = final?.highestBid?.acceptedAt ? Date.parse(final.highestBid.acceptedAt) : null;
  const caughtUp = ok.filter((c) => finalRevision !== null && c.revision >= finalRevision);
  // Last accepted bid -> this watcher first saw the final revision, both on the server clock.
  const lags = lastAcceptedAt === null ? [] : caughtUp.filter((c) => c.revisionServerAt !== null).map((c) => c.revisionServerAt - lastAcceptedAt);
  metrics.mixed = { finalRevision, lastAcceptedAt: final?.highestBid?.acceptedAt ?? null, watchers: ok.length,
    caughtUp: caughtUp.length, activeAtEnd: ok.filter((c) => c.active).length,
    eventsPerConnection: { min: ok.length ? Math.min(...ok.map((c) => c.events)) : null, p50: percentile(ok.map((c) => c.events), 0.5) },
    finalDeliveryLagMs: { p50: percentile(lags, 0.5), p95: percentile(lags, 0.95), max: lags.length ? Math.max(...lags) : null } };
  console.log(`mixed: ${caughtUp.length}/${ok.length} watchers reached revision ${finalRevision}; events p50=${metrics.mixed.eventsPerConnection.p50}; final delivery lag p95=${metrics.mixed.finalDeliveryLagMs.p95 ?? '-'}ms`);
}

main().then(() => finish(false)).catch(async (error) => {
  console.error(`auction SSE load failed: ${error?.message || 'unknown error'}`);
  await finish(true);
  process.exitCode = 1;
});
