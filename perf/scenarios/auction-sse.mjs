#!/usr/bin/env node
// AU-5: hold SSE connections and measure event delivery after a bid. Node 24, no packages.
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
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
const CONNECT_RATE = Number(env.CONNECT_RATE || 200);
const HOLD_SECONDS = Number(env.HOLD_SECONDS || 60);
const PROPAGATION_WAIT_SECONDS = Number(env.PROPAGATION_WAIT_SECONDS || 15);
const CONNECT_WAIT_SECONDS = Number(env.CONNECT_WAIT_SECONDS || 30);
const POST_RUN_CLOSE_WAIT_SECONDS = Number(env.POST_RUN_CLOSE_WAIT_SECONDS || 90);
const STEPS = (env.STEPS || '2000,4000,6000,8000,10000').split(',').map((v) => Number(v.trim()));
const ORGANIZER_EMAIL = env.ORGANIZER_EMAIL || 'organizer1@fanq.com';
const PASSWORD = env.SEED_PASSWORD || '0000';
const EXTENSION_SECONDS = 60;
// The current highest bidder cannot bid again, so two measurement accounts alternate.
const REQUESTED_BIDDERS = ACCOUNTS + 2;

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
const metrics = { organizerLoginMs: null, observerLoginRequested: ACCOUNTS, observerLoginSucceeded: 0,
  observerLoginSeconds: null, measurementBidderIds: [FIRST_ID + ACCOUNTS, FIRST_ID + ACCOUNTS + 1],
  createdAuctionId: null, steps: results, baselineServerMetric: null, activeServerMetric: null,
  serverMetricAfterClose: null, cleanupProbe: null, auctionLifetimeSeconds: null, closedEventCount: 0 };
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
async function readStreamMetric() {
  try {
    const response = await request(`${MANAGEMENT_URL}/actuator/prometheus`, { signal: AbortSignal.timeout(5000) }, '8081');
    if (!response.ok) return null;
    const body = await response.text();
    return safeMetricValue(body, 'auction_stream_connections');
  } catch { return null; }
}
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
      if (key === 'data') fields.data.push(value);
      else if (key === 'id') fields.id = value;
      else if (key === 'event') fields.event = value;
    }
    if (fields.data.length) frames.push(fields);
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
        let data;
        try { data = JSON.parse(frame.data.join('\n')); } catch { continue; }
        const revision = Number(data.revision ?? frame.id);
        if (Number.isFinite(revision)) {
          connection.revision = Math.max(connection.revision, revision);
          const now = performance.now();
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
    closedEvent: false, error: null, controller: new AbortController() };
  connections[index] = connection;
  try {
    const response = await request(`${BASE_URL}/api/auctions/${metrics.createdAuctionId}/stream`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      signal: connection.controller.signal,
    });
    connection.status = response.status;
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
  if (connections.some((c) => !c?.firstEventAt)) correctnessWarnings.push('one or more SSE connections failed before the initial event');
  if (connections.some((c) => c?.disconnected)) correctnessWarnings.push('one or more SSE connections disconnected during the run');
  if (results.some((step) => step.bidOutcome?.status !== 201)) correctnessWarnings.push('measurement bid did not receive 201');
  if (!interrupted && connections.length && metrics.closedEventCount < connections.filter((c) => c?.status === 200).length) {
    correctnessWarnings.push('some successful SSE connections did not receive the final CLOSED event');
  }
  if (metrics.cleanupProbe && metrics.cleanupProbe.status !== 201) correctnessWarnings.push('final SSE cleanup probe bid was not accepted');
  if (connections.length && !interrupted
      && metrics.closedEventCount < connections.filter((c) => c?.status === 200).length
      && !metrics.cleanupProbe) correctnessWarnings.push('final SSE cleanup probe was not recorded');
  if (metrics.baselineServerMetric !== null && metrics.serverMetricAfterClose !== null
      && metrics.serverMetricAfterClose > metrics.baselineServerMetric) {
    correctnessWarnings.push('server still reports SSE connections after client shutdown');
  }
  const summary = { target: 'AU-5 SSE', host: APP_HOST, interrupted, conditions: {
    steps: STEPS, connectRate: CONNECT_RATE, holdSeconds: HOLD_SECONDS, accounts: ACCOUNTS,
    observerIds: { first: FIRST_ID, last: FIRST_ID + ACCOUNTS - 1 },
    measurementBidderIds: metrics.measurementBidderIds, createdAuctionId: metrics.createdAuctionId,
    organizer: ORGANIZER_EMAIL, loginRate: LOGIN_RATE, propagationWaitSeconds: PROPAGATION_WAIT_SECONDS,
    auctionLifetimeSeconds: metrics.auctionLifetimeSeconds, postRunCloseWaitSeconds: POST_RUN_CLOSE_WAIT_SECONDS,
  }, preparation: { organizerLoginMs: metrics.organizerLoginMs, observerLoginRequested: metrics.observerLoginRequested,
    observerLoginSucceeded: metrics.observerLoginSucceeded, observerLoginSeconds: metrics.observerLoginSeconds },
  steps: results, totals: { attemptedConnections: connections.length,
    receivedResponses: connections.filter((c) => c?.status > 0).length,
    connectionFailures: connections.filter((c) => !c?.status).length,
    fiveXx: connections.filter((c) => c?.status >= 500).length, statusCounts },
  correctnessWarnings, activeConnectionsAtStop: connections.filter((c) => c?.active).length,
  failedConnections: connections.filter((c) => c && !c.firstEventAt).map((c) => ({ status: c.status || 0, error: c.error || 'no_initial_event' })),
  disconnectedWhileHeld: connections.filter((c) => c?.disconnected).length,
  closedEventConnections: connections.filter((c) => c?.closedEvent).length,
  serverAuctionStreamConnections: metrics.activeServerMetric,
  serverAuctionStreamConnectionsBefore: metrics.baselineServerMetric,
  serverAuctionStreamConnectionsAfterClose: metrics.serverMetricAfterClose,
  cleanupProbe: metrics.cleanupProbe };
  await mkdir(dirname(SUMMARY_PATH), { recursive: true });
  await writeFile(SUMMARY_PATH, `${JSON.stringify(summary, null, 2)}\n`);
  console.log(`SSE summary: ${SUMMARY_PATH}`);
  for (const step of results) console.log(`step ${step.connections}: attempted=${step.attempted} success=${step.success} failed=${step.failed} disconnected=${step.disconnected} priceChanged=${step.priceChanged}/${step.openConnections} delay p50=${step.deliveryDelayMs.p50 ?? '-'} p95=${step.deliveryDelayMs.p95 ?? '-'} max=${step.deliveryDelayMs.max ?? '-'}`);
  if (correctnessWarnings.length) console.error(`!! 정확성 확인 필요: ${correctnessWarnings.join('; ')}`);
  if (interrupted) process.exitCode = 130;
}
async function finish(interrupted = false) {
  stopping = true;
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
    const gaugeDeadline = performance.now() + 5000;
    while (performance.now() < gaugeDeadline) {
      metrics.serverMetricAfterClose = await readStreamMetric();
      if (metrics.serverMetricAfterClose !== null
          && (metrics.baselineServerMetric === null || metrics.serverMetricAfterClose <= metrics.baselineServerMetric)) break;
      await delay(200);
    }
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
  metrics.auctionLifetimeSeconds = Math.ceil(20 + REQUESTED_BIDDERS / LOGIN_RATE + plannedStageSeconds + EXTENSION_SECONDS);
  const endsAtMs = nowServer + metrics.auctionLifetimeSeconds * 1000;
  metrics.createdAuctionId = await createAuction(organizerToken, new Date(startsAtMs), new Date(endsAtMs));
  const opened = await waitUntilOpen(metrics.createdAuctionId, startsAtMs);
  console.log(`created auction ${metrics.createdAuctionId}; server clock offset ${serverOffsetMs.toFixed(1)}ms; OPEN`);
  metrics.baselineServerMetric = await readStreamMetric();

  const loginStartedAt = performance.now();
  const accountTokens = [];
  const loginEpoch = performance.now() + 1000;
  for (let i = 0; i < ACCOUNTS; i++) {
    const scheduled = loginEpoch + i * 1000 / LOGIN_RATE;
    if (performance.now() < scheduled) await delay(scheduled - performance.now());
    accountTokens.push(await login(`fanq${FIRST_ID + i}@fanq.com`));
    metrics.observerLoginSucceeded++;
  }
  const observerLoginSeconds = (performance.now() - loginStartedAt) / 1000;
  metrics.observerLoginSeconds = observerLoginSeconds;
  measurementTokens = [];
  for (const id of metrics.measurementBidderIds) {
    await delay(1000 / LOGIN_RATE);
    measurementTokens.push(await login(`fanq${id}@fanq.com`));
  }
  console.log(`logged in ${metrics.observerLoginSucceeded}/${ACCOUNTS} observers in ${observerLoginSeconds.toFixed(2)}s at ${LOGIN_RATE}/s; two measurement bidders will alternate`);

  let previous = 0;
  let currentRevision = opened.revision;
  for (const requested of STEPS) {
    if (stopping) break;
    const stageStart = performance.now();
    const step = { connections: requested, attempted: requested, added: requested - previous, success: 0, failed: 0,
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
    step.success = stageConnections.filter((c) => c?.status === 200 && c.firstEventAt !== null).length;
    step.failed = requested - step.success;
    step.statusErrors = stageConnections.reduce((acc, c) => {
      if (!c || !c.firstEventAt) { const key = c?.status ? `HTTP_${c.status}` : (c?.error || 'connect_timeout'); acc[key] = (acc[key] || 0) + 1; }
      return acc;
    }, {});
    step.openConnections = stageConnections.filter((c) => c?.active).length;
    step.disconnected = stageConnections.filter((c) => c?.disconnected).length;
    step.serverMetric = await readStreamMetric();
    metrics.activeServerMetric = step.serverMetric;

    const bidResponseStart = performance.now();
    const measurementToken = measurementTokens[results.length % measurementTokens.length];
    const bidResponse = await request(`${BASE_URL}/api/auctions/${metrics.createdAuctionId}/bids`, {
      method: 'POST', headers: { Authorization: `Bearer ${measurementToken}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
      body: JSON.stringify({ amount: opened.minimumBidAmount + opened.minimumIncrementAmount * (results.length + 1) }),
      signal: AbortSignal.timeout(Number(env.REQUEST_TIMEOUT_SECONDS || 60) * 1000),
    });
    const bidReceivedAt = performance.now();
    let bidBody = {};
    try { bidBody = await bidResponse.json(); } catch { /* report status */ }
    if (bidResponse.status !== 201) {
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
    results.push(step);
    console.log(`step ${requested}: attempted=${step.attempted}, success=${step.success}, failed=${step.failed}, active=${step.openConnections}, streamMetric=${step.serverMetric ?? '-'}, changed=${step.priceChanged}, delay_p95=${step.deliveryDelayMs.p95 ?? '-'}`);
    previous = requested;
  }
}

main().then(() => finish(false)).catch(async (error) => {
  console.error(`auction SSE load failed: ${error?.message || 'unknown error'}`);
  await finish(true);
  process.exitCode = 1;
});
