// AU-1/2/7: stepped auction bidding. See perf/scenarios/README.md.
import exec from 'k6/execution';
import { sleep, fail } from 'k6';
import { auction, users, ROW_OFFSET, checkTarget, detail, get, bid, summarize, summaryTrendStats,
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
const targets = MODE === 'hot' ? [auction.hot_auction_id] : MODE === 'ending' ? [auction.ending_first_id] : Array.from({ length: auction.spread_last_id - auction.spread_first_id + 1 }, (_, i) => auction.spread_first_id + i);
if (!targets.length || targets.some((n) => !Number.isSafeInteger(n) || n < 1)) throw new Error('invalid auction IDs in manifest');
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
export const options = {
  setupTimeout: __ENV.SETUP_TIMEOUT || '1h', teardownTimeout: __ENV.TEARDOWN_TIMEOUT || '15m',
  scenarios: { auction_step: { executor: 'ramping-arrival-rate', startRate: 0, timeUnit: '1s', preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS, stages } },
  thresholds, summaryTrendStats,
};
export function setup() {
  checkTarget();
  const initial = {};
  let endingDetail;
  for (const id of targets) {
    const d = detail(id, users[0].token);
    if (d.status !== 'OPEN') fail(`auction ${id} is ${d.status}; restore seed and refresh`);
    initial[id] = { minimum: d.minimumBidAmount, increment: d.minimumIncrementAmount };
    if (MODE === 'ending') endingDetail = d;
  }
  console.log(`mode=${MODE} auctions=${targets[0]}..${targets[targets.length - 1]} accounts=${users.length} userIds=${users[0].id}..${users[users.length - 1].id} rowOffset=${ROW_OFFSET} planned=${planned}`);
  if (MODE === 'ending') {
    const ends = Date.parse(endingDetail.endsAt), server = Date.parse(endingDetail.serverTime);
    if (!Number.isFinite(ends) || !Number.isFinite(server)) fail('invalid serverTime or endsAt');
    const wait = (ends - server) / 1000 - LEAD;
    if (wait <= 0) fail(`ending auction has less than LEAD=${LEAD}s left; restore seed and increase AUCTION_CLOSE_IN_MINUTES`);
    initialEnd.add(ends);
    console.log(`initial endsAt=${endingDetail.endsAt}; waiting ${wait.toFixed(1)}s until LEAD`);
    sleep(wait);
  }
  return { initial };
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
  const n = exec.scenario.iterationInTest, id = targets[n % targets.length], user = users[n % users.length];
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
export function teardown() {
  if (MODE !== 'ending') return;
  const limit = Number(__ENV.RESULT_WAIT_SECONDS || 600), interval = Number(__ENV.RESULT_POLL_SECONDS || 3);
  if (!(limit > 0 && interval > 0)) { resultMissing.add(1); return; }
  const until = Date.now() + limit * 1000;
  while (Date.now() < until) {
    const res = get(`/api/auctions/${targets[0]}/result`, users[0].token);
    if (res.status === 200) {
      const result = res.json(), d = detail(targets[0], users[0].token);
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
  const first = values('auction_initial_end_ms').min, last = values('auction_final_end_ms').min, completed = values('auction_finalized_at_ms').min;
  const ending = MODE === 'ending' ? { initialEndsAt: first === undefined ? null : new Date(first).toISOString(), finalEndsAt: last === undefined ? null : new Date(last).toISOString(),
    extensionSeconds: first === undefined || last === undefined ? null : (last - first) / 1000, result: (values('auction_result_sold').count || 0) ? 'SOLD' : (values('auction_result_unsold').count || 0) ? 'UNSOLD' : 'UNKNOWN',
    amount: values('auction_result_amount').min ?? null, closeLagSeconds: last === undefined || completed === undefined ? null : (completed - last) / 1000, closedResponses: values('auction_bid_closed').count || 0 } : null;
  const out = summarize(data, { headline: `auction ${MODE} targets ${targets[0]}..${targets[targets.length - 1]}, planned ${planned}`,
    conditions: { mode: MODE, targets, planned, accounts: users.length, firstUserId: users[0].id, lastUserId: users[users.length - 1].id, steps: STEPS, stepRamp: RAMP, stepHold: HOLD, lead: LEAD, peak: PEAK, hold: END_HOLD, preVUs: PRE_VUS, maxVUs: MAX_VUS, abortErrorRate: ABORT_ERROR_RATE, stepResults: steps, ending },
    extraLatency: rates.map((r) => [`step ${r}/s`, `auction_bid_duration{stage:${r}}`]) });
  out.stdout += steps.map((s) => `  step ${s.targetRate}/s accepted ${s.accepted} (${s.acceptedRate === null ? "incomplete" : `${s.acceptedRate}/s`}) requests ${s.requests} errors ${s.errorRate === undefined ? '-' : (s.errorRate * 100).toFixed(2)}%`).join('\n') + '\n';
  if (ending) out.stdout += `  ending ${JSON.stringify(ending)}\n`;
  return out;
}
