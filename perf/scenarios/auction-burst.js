// AU-1 to AU-4: two synchronized one-request-per-VU bursts (default 8k, then 10k).
import http from 'k6/http';
import exec from 'k6/execution';
import { fail, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import {
  BASE_URL, SUMMARY_PATH, checkTarget, detail, tryLogin, createAuction, waitUntilOpen,
  recordLogin, bid, getMeasured, bidServerAccepted,
  resultSold, resultUnsold, resultAmount, resultCloseLag, resultExtension, resultInitialEnd,
  resultFinalEnd, resultFinalizedAt, resultHighestBidAmount, resultMatchesHighest,
  targetAuctionId, phaseStartMetric, serverOffsetMetric, phaseMetric, summaryTrendStats, summarize,
  AUTH_MODE, loadSeedTokens, requireTokensValidUntil,
} from './lib/auction.js';

const TARGET = __ENV.TARGET;
if (!['ending', 'detail', 'list', 'bid'].includes(TARGET)) throw new Error('TARGET must be ending, detail, list, or bid');
const PHASES = (__ENV.PHASES || '8000,10000').split(',').map((s) => Number(s.trim()));
// 판은 하나 또는 둘. 둘이면 앞 판이 작아야 한다 (VU를 큰 판 수만큼 띄우고 앞 판은 그 일부만 쓴다).
if (PHASES.length < 1 || PHASES.length > 2 || PHASES.some((n) => !Number.isSafeInteger(n) || n <= 0)
    || (PHASES.length === 2 && PHASES[0] >= PHASES[1])) {
  throw new Error('PHASES must be one positive integer or two ascending positive integers, e.g. 8000 or 8000,10000');
}
const VUS = PHASES[PHASES.length - 1];
const AUTH_REQUIRED = TARGET === 'ending' || TARGET === 'bid';
// 시드 토큰이면 로그인하지 않는다. 로그인 시간(1만 명 약 11분)과 로그인 CPU가 측정에 섞이지 않는다.
const USE_SEED_TOKENS = AUTH_REQUIRED && AUTH_MODE === 'seed';
const LOGIN_PACED = AUTH_REQUIRED && !USE_SEED_TOKENS;
const FIRST = Number(__ENV.BIDDER_FIRST_ID || 1), LAST = Number(__ENV.BIDDER_LAST_ALLOWED_ID || 96900);
// 첫 판 전 여유. 로그인 때는 늦은 로그인을 흡수하고, 시드 토큰 때는 VU 시작을 기다린다.
const LOGIN_RATE = Number(__ENV.LOGIN_RATE || 15), LOGIN_MARGIN_SECONDS = Number(__ENV.LOGIN_MARGIN_SECONDS || (LOGIN_PACED ? 60 : 30));
const PHASE_GAP_SECONDS = Number(__ENV.PHASE_GAP_SECONDS || (TARGET === 'ending' ? 240 : 60));
const AMOUNT_STEPS = Number(__ENV.AMOUNT_STEPS || 100), REQUEST_TIMEOUT_SECONDS = Number(__ENV.REQUEST_TIMEOUT_SECONDS || 60);
const START_DELAY_SECONDS = Number(__ENV.START_DELAY_SECONDS || 20), AUCTION_DURATION_SECONDS = Number(__ENV.AUCTION_DURATION_SECONDS || 7200);
const ENDING_LEAD_MS = Number(__ENV.ENDING_LEAD_MS || 1000), RESULT_WAIT_SECONDS = Number(__ENV.RESULT_WAIT_SECONDS || 300);
const ORGANIZER_EMAIL = __ENV.ORGANIZER_EMAIL || 'organizer1@fanq.com', PASSWORD = __ENV.SEED_PASSWORD || '0000';
if (![FIRST, LAST, LOGIN_RATE, LOGIN_MARGIN_SECONDS, PHASE_GAP_SECONDS, AMOUNT_STEPS,
  REQUEST_TIMEOUT_SECONDS, START_DELAY_SECONDS, AUCTION_DURATION_SECONDS, RESULT_WAIT_SECONDS].every(Number.isFinite)
  || [LOGIN_RATE, LOGIN_MARGIN_SECONDS, PHASE_GAP_SECONDS, AMOUNT_STEPS, REQUEST_TIMEOUT_SECONDS,
    START_DELAY_SECONDS, AUCTION_DURATION_SECONDS, RESULT_WAIT_SECONDS].some((n) => n <= 0)
  || !Number.isSafeInteger(AMOUNT_STEPS) || !Number.isSafeInteger(FIRST) || !Number.isSafeInteger(LAST)
  || (AUTH_REQUIRED && (FIRST + VUS - 1 > LAST || LAST > 96900))
  || !Number.isSafeInteger(10000 + 100 * (AMOUNT_STEPS - 1)) || ENDING_LEAD_MS < 0) {
  throw new Error('invalid run options or bidder range; IDs must stay at or below BIDDER_LAST_ALLOWED_ID (maximum 96900)');
}
const loginSecondsEstimate = LOGIN_PACED ? Math.ceil(VUS / LOGIN_RATE) : 0;
const loginStartDelaySeconds = LOGIN_PACED ? 10 : 0;
const seedTokens = USE_SEED_TOKENS ? loadSeedTokens(FIRST, VUS) : null;
const LOGIN_START_DELAY_MS = loginStartDelaySeconds * 1000;
const PHASE_GAP_MS = PHASE_GAP_SECONDS * 1000;
const RESULT_POLL_SECONDS = Number(__ENV.RESULT_POLL_SECONDS || 2);
const resultMissing = new Counter('auction_result_missing');
const requestTimeoutMs = (loginSecondsEstimate + LOGIN_MARGIN_SECONDS + PHASE_GAP_SECONDS * (PHASES.length - 1)
  + REQUEST_TIMEOUT_SECONDS + 120) * 1000;

export const options = {
  setupTimeout: '5m', teardownTimeout: `${RESULT_WAIT_SECONDS + 60}s`, summaryTrendStats,
  scenarios: { auction_burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: `${Math.ceil(requestTimeoutMs / 1000)}s` } },
  thresholds: LOGIN_PACED ? { auction_login_success: [{ threshold: 'rate>=0.99', abortOnFail: true, delayAbortEval: '1s' }] } : {},
};

export function setup() {
  checkTarget();
  if (USE_SEED_TOKENS) {
    // 측정이 끝날 때까지 토큰이 살아 있어야 한다. 경매 수명(ending 판)과 결과 대기까지 넉넉히 잡는다.
    const runMs = (START_DELAY_SECONDS + 60 + LOGIN_MARGIN_SECONDS + PHASE_GAP_SECONDS * (PHASES.length - 1)
      + REQUEST_TIMEOUT_SECONDS + RESULT_WAIT_SECONDS + 600) * 1000;
    requireTokensValidUntil(seedTokens, Date.now() + runMs);
  }
  let offsetMs = 0;
  let targets = [];
  let serverNowMs = Date.now();
  let loginStart = null;
  let phaseTimes = [];
  let organizerToken = null;
  if (TARGET !== 'list') {
    const organizer = tryLogin(ORGANIZER_EMAIL, PASSWORD);
    organizerToken = organizer.token;
    if (!organizerToken) {
      if (!AUTH_REQUIRED) throw new Error(`organizer login failed: HTTP ${organizer.status}`);
      console.log(`organizer login failed with HTTP ${organizer.status}; bidder logins will determine the configured login-success threshold`);
    }
  }
  if (TARGET !== 'list' && organizerToken) {
    const beforeClock = Date.now();
    const clock = detail(1, organizerToken);
    const afterClock = Date.now();
    const parsedServer = Date.parse(clock.serverTime);
    if (!Number.isFinite(parsedServer)) throw new Error('existing auction detail has invalid serverTime');
    offsetMs = parsedServer - Math.round((beforeClock + afterClock) / 2);
    serverNowMs = Date.now() + offsetMs;
  }
  if (TARGET !== 'list' && organizerToken) {
    const startsAtMs = serverNowMs + START_DELAY_SECONDS * 1000;
    const pendingTargets = [];
    for (let i = 0; i < PHASES.length; i++) {
      const phase = PHASES[i];
      // Predict the phase from the scheduled opening; the actual VU schedule is
      // recalculated after auction creation and OPEN polling complete.
      const expectedPhaseAt = startsAtMs + 1000 + LOGIN_START_DELAY_MS + loginSecondsEstimate * 1000
        + LOGIN_MARGIN_SECONDS * 1000 + i * PHASE_GAP_MS;
      const endsAtMs = TARGET === 'ending'
        ? expectedPhaseAt + ENDING_LEAD_MS
        : startsAtMs + AUCTION_DURATION_SECONDS * 1000;
      const startsAt = new Date(startsAtMs).toISOString();
      const endsAt = new Date(endsAtMs).toISOString();
      if (Date.parse(endsAt) <= Date.parse(startsAt)) throw new Error('auction endsAt must be after startsAt; increase phase timing');
      const nowTag = new Date().toISOString();
      const id = createAuction(organizerToken, {
        title: `perf ${TARGET} ${phase} ${nowTag}`, itemName: 'Performance test item',
        itemDescription: `Generated for ${TARGET} phase ${phase}`, startingPriceAmount: 10000,
        minimumIncrementAmount: 100, extensionDurationSeconds: 60, startsAt, endsAt,
      });
      pendingTargets.push({ phase, id });
    }
    for (const target of pendingTargets) {
      const opened = waitUntilOpen(target.id, startsAtMs + 60000);
      targets.push({ ...target, minimum: opened.minimumBidAmount, increment: opened.minimumIncrementAmount,
        originalEndsAt: opened.endsAt });
    }
  }

  // Start paced logins only after all auctions are OPEN. This prevents setup
  // duration from making scheduled login requests bunch up at VU startup.
  loginStart = LOGIN_PACED ? Date.now() + LOGIN_START_DELAY_MS : Date.now();
  const firstPhaseAt = loginStart + loginSecondsEstimate * 1000 + LOGIN_MARGIN_SECONDS * 1000;
  phaseTimes = PHASES.map((_, index) => firstPhaseAt + index * PHASE_GAP_MS);
  const loginStartAt = LOGIN_PACED ? loginStart : null;
  const firstT = phaseTimes[0];
  const data = { target: TARGET, phases: PHASES, vus: VUS, targets, phaseTimes, offsetMs, loginStartAt,
    firstPhaseAt: firstT, loginRequested: LOGIN_PACED ? VUS : 0, loginSecondsEstimate, authMode: AUTH_REQUIRED ? AUTH_MODE : null,
    loginRate: LOGIN_RATE, loginMarginSeconds: LOGIN_MARGIN_SECONDS, phaseGapSeconds: PHASE_GAP_SECONDS,
    loginStartDelaySeconds, organizer: ORGANIZER_EMAIL, setupLoginFailed: !organizerToken && TARGET !== 'list',
    amountSteps: AMOUNT_STEPS, requestTimeoutSeconds: REQUEST_TIMEOUT_SECONDS,
    endingLeadMs: ENDING_LEAD_MS, resultWaitSeconds: RESULT_WAIT_SECONDS };
  console.log(`target=${TARGET} phases=${PHASES.join(' -> ')} VUs=${VUS} auth=${AUTH_REQUIRED ? AUTH_MODE : 'none'} loginEstimate=${loginSecondsEstimate}s offset=${offsetMs.toFixed(1)}ms T=${phaseTimes.map((t) => new Date(t).toISOString()).join(',')} auctions=${targets.map((t) => t.id).join(',') || 'none'}`);
  return data;
}

export default function (data) {
  const vuIndex = exec.vu.idInTest - 1;
  let token = '';
  if (USE_SEED_TOKENS) token = seedTokens[vuIndex];
  else if (AUTH_REQUIRED) {
    const scheduledLoginAt = data.loginStartAt + vuIndex * 1000 / LOGIN_RATE;
    if (Date.now() < scheduledLoginAt) sleep((scheduledLoginAt - Date.now()) / 1000);
    const started = Date.now();
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: `fanq${FIRST + vuIndex}@fanq.com`, password: PASSWORD }), {
      headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup/login' }, timeout: '30s',
    });
    let accessToken;
    try { accessToken = res.json('accessToken'); } catch (_) { /* invalid account/password */ }
    const elapsed = Date.now() - started;
    const late = Date.now() > data.firstPhaseAt;
    recordLogin(res.status === 200 && !!accessToken, elapsed, late, (Date.now() - data.loginStartAt) / 1000);
    if (res.status === 200 && accessToken) token = accessToken;
    if (!token) return; // the login-success threshold aborts the run before its first phase
  }
  if (data.setupLoginFailed) fail('organizer login failed; no auction was created');

  for (let index = 0; index < data.phases.length; index++) {
    const phase = data.phases[index];
    if (vuIndex >= phase) continue;
    const at = data.phaseTimes[index];
    const phaseSummary = phaseMetric(phase);
    phaseSummary.phaseStart.add(at);
    phaseSummary.serverOffset.add(data.offsetMs);
    phaseStartMetric.add(at, { phase: String(phase) });
    serverOffsetMetric.add(data.offsetMs, { phase: String(phase) });
    if (Date.now() < at) sleep((at - Date.now()) / 1000);
    const target = data.targets.find((t) => t.phase === phase);
    if (target) { targetAuctionId.add(target.id, { phase: String(phase) }); phaseSummary.targetId.add(target.id); }
    if (TARGET === 'detail') getMeasured(`/api/auctions/${target.id}`, phase, at, 'detail');
    else if (TARGET === 'list') getMeasured('/api/auctions?status=OPEN&page=0&size=20', phase, at, 'list');
    else {
      const steps = Math.floor(Math.random() * AMOUNT_STEPS);
      bid({ id: FIRST + vuIndex, token }, target.id, target.minimum + target.increment * steps,
        phase, at, REQUEST_TIMEOUT_SECONDS, target.originalEndsAt, data.offsetMs);
    }
  }
}

export function teardown(data) {
  if (!data || !data.targets.length) return;
  if (TARGET === 'bid') {
    sleep(15);
    for (const target of data.targets) {
      const d = detail(target.id);
      const serverAccepted = d.revision - 1; // OPEN transition is +1; each accepted bid is +1.
      bidServerAccepted.add(serverAccepted, { phase: String(target.phase) });
      phaseMetric(target.phase).bidServerAccepted.add(serverAccepted);
      console.log(`phase ${target.phase} auction ${target.id}: revision=${d.revision}, serverAccepted=${serverAccepted}`);
    }
    return;
  }
  if (TARGET !== 'ending') return;
  const pending = new Set(data.targets.map((t) => t.phase));
  const until = Date.now() + RESULT_WAIT_SECONDS * 1000;
  while (pending.size && Date.now() < until) {
    for (const target of data.targets) {
      if (!pending.has(target.phase)) continue;
      const response = http.get(`${BASE_URL}/api/auctions/${target.id}/result`, { tags: { name: 'teardown/result', phase: String(target.phase) }, timeout: '10s' });
      if (response.status !== 200) continue;
      const result = response.json();
      const d = detail(target.id);
      const phaseTag = { phase: String(target.phase) };
      const acceptedCount = d.revision - 2; // OPEN transition and close are +1 each; accepted bids are +1.
      bidServerAccepted.add(acceptedCount, phaseTag);
      const phaseSummary = phaseMetric(target.phase);
      const initialEnd = Date.parse(target.originalEndsAt), finalEnd = Date.parse(d.endsAt);
      resultExtension.add(finalEnd - initialEnd, phaseTag);
      resultCloseLag.add(Date.parse(result.finalizedAt) - finalEnd, phaseTag);
      resultInitialEnd.add(initialEnd, phaseTag);
      resultFinalEnd.add(finalEnd, phaseTag);
      resultFinalizedAt.add(Date.parse(result.finalizedAt), phaseTag);
      phaseSummary.bidServerAccepted.add(acceptedCount);
      phaseSummary.resultExtension.add(finalEnd - initialEnd);
      phaseSummary.resultCloseLag.add(Date.parse(result.finalizedAt) - finalEnd);
      phaseSummary.resultInitialEnd.add(initialEnd);
      phaseSummary.resultFinalEnd.add(finalEnd);
      phaseSummary.resultFinalizedAt.add(Date.parse(result.finalizedAt));
      if (result.type === 'SOLD') {
        resultSold.add(1, phaseTag); resultAmount.add(result.amount, phaseTag);
        phaseSummary.resultSold.add(1);
        phaseSummary.resultAmount.add(result.amount);
        if (d.highestBid && Number.isFinite(d.highestBid.amount)) {
          resultHighestBidAmount.add(d.highestBid.amount, phaseTag);
          phaseSummary.resultHighestBidAmount.add(d.highestBid.amount);
        }
        resultMatchesHighest.add(!!d.highestBid && result.winnerNickname === d.highestBid.nickname
          && result.amount === d.highestBid.amount, phaseTag);
        phaseSummary.resultMatchesHighest.add(!!d.highestBid && result.winnerNickname === d.highestBid.nickname
          && result.amount === d.highestBid.amount);
      } else if (result.type === 'UNSOLD') { resultUnsold.add(1, phaseTag); phaseSummary.resultUnsold.add(1); }
      pending.delete(target.phase);
      console.log(`phase ${target.phase} auction ${target.id}: ${result.type} amount=${result.amount ?? '-'} revision=${d.revision} serverAccepted=${acceptedCount} endsAt=${d.endsAt} finalizedAt=${result.finalizedAt}`);
    }
    if (pending.size) sleep(RESULT_POLL_SECONDS);
  }
  for (const phase of pending) {
    const target = data.targets.find((item) => item.phase === phase);
    const d = detail(target.id);
    const serverAccepted = d.revision - (d.status === 'CLOSED' ? 2 : 1);
    bidServerAccepted.add(serverAccepted, { phase: String(phase) });
    phaseMetric(phase).bidServerAccepted.add(serverAccepted);
    resultMissing.add(1, { phase: String(phase) });
    phaseMetric(phase).resultMissing.add(1);
    console.log(`phase ${phase} auction ${target.id}: result not available before RESULT_WAIT_SECONDS; status=${d.status} revision=${d.revision} serverAccepted=${serverAccepted}`);
  }
}

export function handleSummary(data) {
  if (!data?.metrics) return { stdout: 'auction run ended before measurements; inspect the setup error above\n' };
  const metric = (phase, suffix) => data.metrics[phaseMetric(phase).prefix + suffix]?.values || {};
  const targetIds = PHASES.map((phase) => metric(phase, '_target_id').min).filter(Number.isFinite);
  const summary = summarize(data, { target: TARGET, phases: PHASES, vus: VUS,
    targets: targetIds,
    bidderFirstId: AUTH_REQUIRED ? FIRST : null, bidderLastId: AUTH_REQUIRED ? FIRST + VUS - 1 : null,
    authMode: AUTH_REQUIRED ? AUTH_MODE : null,
    loginRequested: LOGIN_PACED ? VUS : 0, loginRate: LOGIN_PACED ? LOGIN_RATE : null,
    loginSecondsEstimate: AUTH_REQUIRED ? loginSecondsEstimate : 0, loginStartDelaySeconds,
    loginMarginSeconds: LOGIN_MARGIN_SECONDS, phaseGapSeconds: PHASE_GAP_SECONDS,
    amountSteps: TARGET === 'bid' || TARGET === 'ending' ? AMOUNT_STEPS : null,
    requestTimeoutSeconds: REQUEST_TIMEOUT_SECONDS, endingLeadMs: TARGET === 'ending' ? ENDING_LEAD_MS : null,
    organizer: TARGET === 'list' ? null : ORGANIZER_EMAIL });
  for (const phase of PHASES) {
    const server = metric(phase, '_server_accepted').min;
    const acceptedForPhase = metric(phase, '_accepted').count || 0;
    if (TARGET === 'bid' && server !== undefined) {
      summary.phases.find((p) => p.phase === phase).bidReconciliation = { acceptedResponses: acceptedForPhase, serverAccepted: server, difference: server - acceptedForPhase };
    }
    if (TARGET === 'ending') {
      const phaseSummary = summary.phases.find((p) => p.phase === phase);
      phaseSummary.bidReconciliation = { acceptedResponses: acceptedForPhase,
        serverAccepted: server ?? null, difference: server === undefined ? null : server - acceptedForPhase };
      phaseSummary.closedResponses = metric(phase, '_closed').count || 0;
      phaseSummary.closedBeforeOriginalEnd = metric(phase, '_closed_before_original_end').count || 0;
      const latestAcceptedAt = metric(phase, '_latest_accepted_at_ms').max;
      const latestAmount = metric(phase, '_latest_accepted_amount').max;
      const amountMetric = metric(phase, '_result_amount').min;
      const finalEndMs = metric(phase, '_result_final_end_ms').min;
      const acceptedAfterFinalEnd = latestAcceptedAt !== undefined && finalEndMs !== undefined
        ? latestAcceptedAt > finalEndMs : null;
      const sold = (metric(phase, '_result_sold').count || 0) > 0;
      const unsold = (metric(phase, '_result_unsold').count || 0) > 0;
      const result = { type: sold ? 'SOLD' : unsold ? 'UNSOLD' : 'UNKNOWN', amount: amountMetric ?? null,
        latestAcceptedResponseAt: latestAcceptedAt === undefined ? null : new Date(latestAcceptedAt).toISOString(),
        latestAcceptedResponseAmount: latestAmount ?? null,
        winnerMatchesLatestAcceptedResponse: sold && latestAmount !== null ? amountMetric === latestAmount : null,
        acceptedAfterFinalEnd,
        winnerMatchesCurrentHighestBid: metric(phase, '_result_matches_highest').rate ?? null,
        currentHighestBidAmount: metric(phase, '_result_highest_bid_amount').min ?? null,
        resultMissing: metric(phase, '_result_missing').count || 0,
        originalEndsAt: metric(phase, '_result_initial_end_ms').min === undefined ? null : new Date(metric(phase, '_result_initial_end_ms').min).toISOString(),
        finalEndsAt: metric(phase, '_result_final_end_ms').min === undefined ? null : new Date(metric(phase, '_result_final_end_ms').min).toISOString(),
        finalizedAt: metric(phase, '_result_finalized_at_ms').min === undefined ? null : new Date(metric(phase, '_result_finalized_at_ms').min).toISOString(),
        extensionMs: metric(phase, '_result_extension_ms').min ?? null,
        closeLagMs: metric(phase, '_result_close_lag_ms').min ?? null };
      phaseSummary.ending = result;
      if (result.winnerMatchesLatestAcceptedResponse === false || result.winnerMatchesCurrentHighestBid === 0
        || (phaseSummary.bidReconciliation.difference !== null && phaseSummary.bidReconciliation.difference !== 0)
        || phaseSummary.bidReconciliation.difference === null || result.type === 'UNKNOWN'
        || result.resultMissing > 0 || phaseSummary.closedBeforeOriginalEnd > 0 || acceptedAfterFinalEnd !== false) {
        summary.correctnessWarnings.push(`phase ${phase}: ending correctness check needs attention`);
      }
    }
  }
  if (TARGET === 'bid' && summary.phases.some((p) => p.bidReconciliation && p.bidReconciliation.difference !== 0)) {
    summary.correctnessWarnings.push('server accepted count differs from k6 201 responses');
  }
  const out = { stdout: summary.stdout + summary.phases.map((p) => p.bidReconciliation
    ? 'phase ' + p.phase + ': bid reconciliation ' + JSON.stringify(p.bidReconciliation) + (p.ending ? ' ending=' + JSON.stringify(p.ending) : '') : '').join('\n')
    + (summary.correctnessWarnings.length ? '\n!! 정확성 확인 필요: ' + summary.correctnessWarnings.join('; ') : '') + '\n' };
  if (SUMMARY_PATH) {
    const { stdout, ...summaryJson } = summary;
    out[SUMMARY_PATH] = JSON.stringify(summaryJson, null, 2);
  }
  return out;
}
