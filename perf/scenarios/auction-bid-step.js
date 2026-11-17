// AU-1/2/7: stepped auction bidding. See perf/scenarios/README.md.
import exec from 'k6/execution';
import { sleep, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { SETUP, auction, users, ROW_OFFSET, checkTarget, detail, get, bid, summarize, summaryTrendStats, loginOrganizer, loginBidders, createAuction,
  initialEnd, finalEnd, finalizedAt, resultAmount, resultSold, resultUnsold, resultMissing } from './lib/auction.js';

const MODE = __ENV.MODE || 'hot';
if (!['hot', 'spread', 'ending'].includes(MODE)) throw new Error(`invalid MODE: ${MODE}`);
const STEPS = (__ENV.STEPS || '10,25,50,100,200,400').split(',').map((s) => Number(s.trim()));
if (STEPS.some((n) => !Number.isFinite(n) || n <= 0) || new Set(STEPS).size !== STEPS.length) throw new Error('STEPS must contain distinct positive numbers');
function seconds(value) {
  const m = /^(\d+)(s|m)$/.exec(value);
  if (!m || Number(m[1]) === 0) throw new Error(`invalid duration: ${value}`);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}
const RAMP = __ENV.STEP_RAMP || '10s', HOLD = __ENV.STEP_HOLD || '60s';
const rampSeconds = seconds(RAMP), holdSeconds = seconds(HOLD);
const LEAD = Number(__ENV.LEAD || 120), PEAK = Number(__ENV.PEAK || 50), END_HOLD = __ENV.HOLD || '120s';
const endHoldSeconds = seconds(END_HOLD);
if (!Number.isInteger(LEAD) || LEAD < 2 || !Number.isFinite(PEAK) || PEAK <= 0) throw new Error('LEAD must be an integer >= 2 and PEAK must be positive');
const PRE_VUS = Number(__ENV.PRE_VUS || 200), MAX_VUS = Number(__ENV.MAX_VUS || 3000);
const ABORT_ERROR_RATE = Number(__ENV.ABORT_ERROR_RATE || 0.05);
if (!(ABORT_ERROR_RATE > 0 && ABORT_ERROR_RATE <= 1) || !Number.isInteger(PRE_VUS) || !Number.isInteger(MAX_VUS) || PRE_VUS < 1 || MAX_VUS < PRE_VUS) throw new Error('invalid VU limits or ABORT_ERROR_RATE');
const BIDDERS = Number(__ENV.BIDDERS || 1000), FIRST = Number(__ENV.BIDDER_FIRST_ID || 1), LAST = Number(__ENV.BIDDER_LAST_ALLOWED_ID || 96900);
const SPREAD_COUNT = Number(__ENV.SPREAD_COUNT || 100), LOGIN_BATCH = Number(__ENV.LOGIN_BATCH || 50);
const ORGANIZER_EMAIL = __ENV.ORGANIZER_EMAIL || 'organizer1@fanq.com', PASSWORD = __ENV.SEED_PASSWORD || '0000';
function setupSeconds(value) {
  const match = /^(\d+)(s|m|h)$/.exec(value);
  if (!match || Number(match[1]) < 1) throw new Error(`invalid duration: ${value}`);
  return Number(match[1]) * ({ s: 1, m: 60, h: 3600 })[match[2]];
}
const START_DELAY = setupSeconds(__ENV.START_DELAY || '20s'), AUCTION_DURATION = setupSeconds(__ENV.AUCTION_DURATION || '2h');
if (SETUP === 'self' && (![BIDDERS, FIRST, LAST, SPREAD_COUNT, LOGIN_BATCH].every((n) => Number.isSafeInteger(n) && n > 0) || FIRST + BIDDERS - 1 > LAST)) throw new Error('invalid bidder range or setup counts; requested IDs must not exceed BIDDER_LAST_ALLOWED_ID');
const setupAuctionId = new Trend('auction_setup_id');
const setupLoginSeconds = new Trend('auction_setup_login_seconds');
const setupLoginSucceeded = new Trend('auction_setup_login_succeeded');
const setupFirstId = new Trend('auction_setup_first_user_id');
const setupLastId = new Trend('auction_setup_last_user_id');
const manifestTargets = SETUP === 'self' ? [] : MODE === 'hot' ? [auction.hot_auction_id] : MODE === 'ending' ? [auction.ending_first_id] : Array.from({ length: auction.spread_last_id - auction.spread_first_id + 1 }, (_, i) => auction.spread_first_id + i);
if (SETUP === 'manifest' && (!manifestTargets.length || manifestTargets.some((n) => !Number.isSafeInteger(n) || n < 1))) throw new Error('invalid auction IDs in manifest');
const stages = [];
let planned = 0, previous = 0;
if (MODE === 'ending') {
  const rise = Math.floor(LEAD / 2);
  stages.push({ target: PEAK, duration: `${rise}s` }, { target: PEAK, duration: END_HOLD }, { target: 0, duration: '5s' });
  planned = PEAK * rise / 2 + PEAK * endHoldSeconds + PEAK * 2.5;
} else {
  for (const rate of STEPS) { stages.push({ target: rate, duration: RAMP }, { target: rate, duration: HOLD }); planned += (previous + rate) / 2 * rampSeconds + rate * holdSeconds; previous = rate; }
  stages.push({ target: 0, duration: '5s' }); planned += previous * 2.5;
}
planned = Math.ceil(planned);
const thresholds = { auction_error_rate: [{ threshold: `rate<${ABORT_ERROR_RATE}`, abortOnFail: true, delayAbortEval: '30s' }] };
for (const rate of (MODE === 'ending' ? [PEAK] : STEPS)) {
  thresholds[`auction_bid_duration{stage:${rate}}`] = [];
  thresholds[`auction_bid_accepted{stage:${rate}}`] = [];
  thresholds[`auction_error_rate{stage:${rate}}`] = [];
  thresholds[`http_reqs{stage:${rate}}`] = [];
}
if (SETUP === 'self') for (let i = 0; i < (MODE === 'spread' ? SPREAD_COUNT : 1); i++) thresholds[`auction_setup_id{index:${i}}`] = [];
export const options = {
  batch: SETUP === 'self' ? LOGIN_BATCH : 20, batchPerHost: SETUP === 'self' ? LOGIN_BATCH : 6,
  setupTimeout: __ENV.SETUP_TIMEOUT || '1h', teardownTimeout: __ENV.TEARDOWN_TIMEOUT || '15m',
  scenarios: { auction_step: { executor: 'ramping-arrival-rate', startRate: 0, timeUnit: '1s', preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, stages } },
  thresholds, summaryTrendStats,
};
export function setup() {
  checkTarget();
  let targets = manifestTargets, bidders = users;
  let loginInfo = null;
  if (SETUP === 'self') {
    const organizerToken = loginOrganizer(ORGANIZER_EMAIL, PASSWORD);
    loginInfo = loginBidders(FIRST, BIDDERS, LOGIN_BATCH, PASSWORD);
    bidders = loginInfo.users;
    setupLoginSeconds.add(loginInfo.seconds); setupLoginSucceeded.add(loginInfo.succeeded);
    setupFirstId.add(bidders[0].id); setupLastId.add(bidders[bidders.length - 1].id);
    const clock = detail(1, organizerToken), serverTime = Date.parse(clock.serverTime);
    if (!Number.isFinite(serverTime)) fail('cannot obtain valid serverTime from seeded auction 1');
    const offset = serverTime - Date.now();
    const starts = new Date(Date.now() + offset + START_DELAY * 1000);
    const ends = new Date(starts.getTime() + (MODE === 'ending' ? LEAD + 30 : AUCTION_DURATION) * 1000);
    targets = [];
    for (let i = 0; i < (MODE === 'spread' ? SPREAD_COUNT : 1); i++) {
      const id = createAuction(organizerToken, { title: `perf ${MODE} ${starts.toISOString()} ${i + 1}`,
        itemName: 'Performance test item', itemDescription: 'Performance test auction',
        startingPriceAmount: 10000, minimumIncrementAmount: 100, extensionDurationSeconds: 60,
        startsAt: starts.toISOString(), endsAt: ends.toISOString() });
      targets.push(id); setupAuctionId.add(id, { index: String(i) });
    }
    console.log(`created ${targets.length} auctions, startsAt=${starts.toISOString()}, endsAt=${ends.toISOString()}`);
    let pending = targets.slice();
    while (pending.length) {
      pending = pending.filter((id) => detail(id, organizerToken).status !== 'OPEN');
      if (!pending.length) break;
      if (Date.now() + offset > starts.getTime() + 60000) fail(`auctions did not open; check the auction scheduler (pending ${pending.length})`);
      sleep(1);
    }
  }
  const initial = {};
  let endingDetail;
  for (const id of targets) {
    const d = detail(id, bidders[0].token);
    if (d.status !== 'OPEN') fail(`auction ${id} is ${d.status}; restore seed and refresh`);
    initial[id] = { minimum: d.minimumBidAmount, increment: d.minimumIncrementAmount };
    if (MODE === 'ending') endingDetail = d;
  }
  console.log(`mode=${MODE} setup=${SETUP} auctions=${targets[0]}..${targets[targets.length - 1]} accounts=${bidders.length} userIds=${bidders[0].id}..${bidders[bidders.length - 1].id} rowOffset=${ROW_OFFSET} planned=${planned}`);
  if (MODE === 'ending') {
    const ends = Date.parse(endingDetail.endsAt), server = Date.parse(endingDetail.serverTime);
    if (!Number.isFinite(ends) || !Number.isFinite(server)) fail('invalid serverTime or endsAt');
    const wait = (ends - server) / 1000 - LEAD;
    if (wait <= 0) fail(`ending auction has less than LEAD=${LEAD}s left; restore seed and increase AUCTION_CLOSE_IN_MINUTES`);
    initialEnd.add(ends);
    console.log(`initial endsAt=${endingDetail.endsAt}; waiting ${wait.toFixed(1)}s until LEAD`);
    sleep(wait);
  }
  return SETUP === 'self' ? { initial, targets, bidders } : { initial };
}
let minimums;
function stageTag() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  if (MODE === 'ending') return elapsed >= Math.floor(LEAD / 2) && elapsed < Math.floor(LEAD / 2) + endHoldSeconds ? String(PEAK) : 'ramp';
  const i = Math.floor(elapsed / (rampSeconds + holdSeconds));
  return i < STEPS.length && elapsed - i * (rampSeconds + holdSeconds) >= rampSeconds ? String(STEPS[i]) : 'ramp';
}
export default function (setupData) {
  minimums ||= {};
  const targets = setupData.targets || manifestTargets, bidders = setupData.bidders || users;
  const n = exec.scenario.iterationInTest, id = targets[n % targets.length], user = bidders[n % bidders.length];
  const increment = setupData.initial[id].increment;
  const min = minimums[id] || setupData.initial[id].minimum;
  let result = bid(user, id, min + increment * Math.floor(Math.random() * 3), { stage: stageTag(), retry: 'false' });
  if (result.outcome === 'accepted') minimums[id] = Math.max(min, result.body.amount + increment);
  if (result.outcome === 'too_low' && Number.isFinite(result.body.details?.minimumBidAmount)) {
    minimums[id] = Math.max(minimums[id] || 0, result.body.details.minimumBidAmount);
    result = bid(user, id, minimums[id] + increment * Math.floor(Math.random() * 3), { stage: stageTag(), retry: 'true' });
    if (result.outcome === 'accepted') minimums[id] = Math.max(minimums[id], result.body.amount + increment);
    else if (result.outcome === 'too_low' && Number.isFinite(result.body.details?.minimumBidAmount)) minimums[id] = Math.max(minimums[id], result.body.details.minimumBidAmount);
  }
}
export function teardown(setupData) {
  if (MODE !== 'ending' || !setupData) return;
  const targets = setupData.targets || manifestTargets, bidders = setupData.bidders || users;
  const limit = Number(__ENV.RESULT_WAIT_SECONDS || 600), interval = Number(__ENV.RESULT_POLL_SECONDS || 3);
  if (!(limit > 0 && interval > 0)) { resultMissing.add(1); return; }
  const until = Date.now() + limit * 1000;
  while (Date.now() < until) {
    const res = get(`/api/auctions/${targets[0]}/result`, bidders[0].token);
    if (res.status === 200) {
      const result = res.json(), d = detail(targets[0], bidders[0].token);
      finalEnd.add(Date.parse(d.endsAt)); finalizedAt.add(Date.parse(result.finalizedAt));
      if (result.type === 'SOLD') { resultSold.add(1); resultAmount.add(result.amount); }
      else if (result.type === 'UNSOLD') resultUnsold.add(1);
      else { resultMissing.add(1); return; }
      console.log(`auction result=${result.type} amount=${result.amount ?? '-'} finalEndsAt=${d.endsAt} finalizedAt=${result.finalizedAt}`);
      return;
    }
    if (res.status !== 404) console.warn(`result request returned ${res.status}; retrying`);
    sleep(interval);
  }
  resultMissing.add(1); console.error(`result still unavailable after ${limit}s`);
}
export function handleSummary(data) {
  const values = (key) => data.metrics[key]?.values || {};
  const rates = MODE === 'ending' ? [PEAK] : STEPS;
  const secondsHeld = MODE === 'ending' ? endHoldSeconds : holdSeconds;
  const aborted = Object.values(data.metrics.auction_error_rate?.thresholds || {}).some((t) => !t.ok);
  const steps = rates.map((rate) => ({ targetRate: rate, requests: values(`http_reqs{stage:${rate}}`).count || 0,
    accepted: values(`auction_bid_accepted{stage:${rate}}`).count || 0,
    acceptedRate: aborted ? null : Number(((values(`auction_bid_accepted{stage:${rate}}`).count || 0) / secondsHeld).toFixed(1)),
    errorRate: values(`auction_error_rate{stage:${rate}}`).rate, latency: values(`auction_bid_duration{stage:${rate}}`) }));
  const targets = SETUP === 'self' ? Array.from({ length: MODE === 'spread' ? SPREAD_COUNT : 1 }, (_, i) => values(`auction_setup_id{index:${i}}`).min).filter((id) => id !== undefined) : manifestTargets;
  const accounts = SETUP === 'self' ? (values('auction_setup_login_succeeded').min ?? 0) : users.length;
  const firstUserId = SETUP === 'self' ? (values('auction_setup_first_user_id').min ?? null) : users[0].id;
  const lastUserId = SETUP === 'self' ? (values('auction_setup_last_user_id').min ?? null) : users[users.length - 1].id;
  const first = values('auction_initial_end_ms').min, last = values('auction_final_end_ms').min, completed = values('auction_finalized_at_ms').min;
  const ending = MODE === 'ending' ? { initialEndsAt: first === undefined ? null : new Date(first).toISOString(), finalEndsAt: last === undefined ? null : new Date(last).toISOString(),
    extensionSeconds: first === undefined || last === undefined ? null : (last - first) / 1000, result: (values('auction_result_sold').count || 0) ? 'SOLD' : (values('auction_result_unsold').count || 0) ? 'UNSOLD' : 'UNKNOWN',
    amount: values('auction_result_amount').min ?? null, closeLagSeconds: last === undefined || completed === undefined ? null : (completed - last) / 1000, closedResponses: values('auction_bid_closed').count || 0 } : null;
  const out = summarize(data, { headline: `auction ${MODE} targets ${targets[0]}..${targets[targets.length - 1]}, planned ${planned}`,
    conditions: { mode: MODE, ...(SETUP === 'self' ? { setup: SETUP, organizer: ORGANIZER_EMAIL, loginRequested: BIDDERS, loginSucceeded: accounts, loginSeconds: values('auction_setup_login_seconds').min ?? null } : {}), targets, planned, accounts, firstUserId, lastUserId, steps: STEPS, stepRamp: RAMP, stepHold: HOLD, lead: LEAD, peak: PEAK, hold: END_HOLD, preVUs: PRE_VUS, maxVUs: MAX_VUS, abortErrorRate: ABORT_ERROR_RATE, stepResults: steps, ending },
    extraLatency: rates.map((r) => [`step ${r}/s`, `auction_bid_duration{stage:${r}}`]) });
  out.stdout += steps.map((s) => `  step ${s.targetRate}/s accepted ${s.accepted} (${s.acceptedRate === null ? "incomplete" : `${s.acceptedRate}/s`}) requests ${s.requests} errors ${s.errorRate === undefined ? '-' : (s.errorRate * 100).toFixed(2)}%`).join('\n') + '\n';
  if (ending) out.stdout += `  ending ${JSON.stringify(ending)}\n`;
  if (SETUP === 'self') out.stdout += '  정합성은 서버에서 perf/ec2/perf-ec2.sh verify base로 확인 (그날 돌린 모든 판을 한 번에)\n';
  return out;
}
