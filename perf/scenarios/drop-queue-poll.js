// 대기열 폴링 측정: 대기자 N명을 고정해 두고 상태 조회 비용과 수용 가능한 대기 인원을 잰다.
// 계획은 docs/testing/drop-performance-plan.md, 절차는 drop-performance-procedure.md 5절. 사용법은 perf/scenarios/README.md.
//
// 앱은 restless.drop.queue.required=true, 입장 작업은 끈다(RESTLESS_DROP_QUEUE_SCHEDULER_ENABLED=false).
// 시드는 queue prefill 0이다. 미리 채운 대기 기록은 heartbeat가 끊겨 첫 조회에서 닫힌다.
// 회원은 ENTER_RATE로 한 명씩 진입하고, 진입하자마자 POLL 간격으로 조회를 시작해 측정이 끝날 때까지 이어 간다.
// 구간: fill(N명 진입) → settle(인원 안정화) → warmup → measure. 조회는 시작한 구간에 귀속한다.
import { sleep } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  DROP_ID, MANAGEMENT_URL, loadUsers, summaryTrendStats, checkTarget, skipped, summarize, tracked, durationMs,
  emptyThresholds, latencyLine, pct, ROW_OFFSET, variantFor,
} from './lib/drop-reservation.js';
import { enterQueue, pollQueue, nextWaitSeconds } from './lib/drop-flow.js';

const WAITERS = Number(__ENV.WAITERS);
if (!(WAITERS > 0)) throw new Error('WAITERS is required (e.g. 100, 500, 1000, 2000, 5000, 10000)');
const ENTER_RATE = Number(__ENV.ENTER_RATE || 50);
const POLL_S = Number(__ENV.POLL_SECONDS || 2);
const FILL_MS = Math.ceil(WAITERS / ENTER_RATE) * 1000;
// 정수 스케줄링으로 마지막 몇 명이 빠지지 않게 진입 구간을 조금 늘린다. 넘친 iteration은 요청 없이 끝난다.
const MARGIN_MS = durationMs(__ENV.MARGIN || '1s');
const SPARE_VUS = Math.ceil((ENTER_RATE * MARGIN_MS) / 1000) + 10;
const SETTLE_MS = durationMs(__ENV.SETTLE || '10s');
const WARM_MS = durationMs(__ENV.WARM || '60s');
const MEASURE_MS = durationMs(__ENV.MEASURE || '120s');
const AFTER_FILL_MS = SETTLE_MS + WARM_MS + MEASURE_MS;
const users = loadUsers(WAITERS);

// 대기를 유지한 회원과 잃은 회원. 잃은 회원이 있으면 N명 고정 실험은 실패다.
const kept = new Counter('drop_queue_waiter_kept');
const lost = new Counter('drop_queue_waiter_lost');
// 입장 작업을 껐는데 ADMITTED가 보이면 조건이 틀렸으므로 멈춘다.
const admitted = new Counter('drop_queue_unexpected_admission');

const PHASES = ['fill', 'settle', 'warmup', 'measure'];
const LOSS_REASONS = ['enter_failed', 'sold_out', 'closed', 'expired', 'consumed', 'unavailable', 'admitted'];
const subMetrics = [
  'drop_eval_latency{api:enter}', 'drop_unexpected_error{api:enter}',
  'drop_queue_busy{api:enter}', 'drop_queue_busy{api:poll,phase:measure}',
  ...PHASES.flatMap((p) => [`drop_eval_latency{api:poll,phase:${p}}`, `drop_unexpected_error{api:poll,phase:${p}}`,
    `drop_queue_poll{phase:${p}}`]),
  'drop_queue_poll{result:waiting,phase:measure}', 'drop_queue_poll{result:timeout,phase:measure}',
  ...LOSS_REASONS.map((r) => `drop_queue_waiter_lost{reason:${r}}`),
];

export const options = {
  scenarios: {
    waiters: {
      executor: 'constant-arrival-rate',
      rate: ENTER_RATE,
      timeUnit: '1s',
      duration: `${FILL_MS + MARGIN_MS}ms`,
      // 대기자마다 VU 하나가 측정이 끝날 때까지 조회한다. 여유 구간의 넘친 iteration이 쓸 VU를 더 둔다.
      preAllocatedVUs: WAITERS + SPARE_VUS,
      maxVUs: WAITERS + SPARE_VUS,
      gracefulStop: `${Math.ceil(AFTER_FILL_MS / 1000) + 30}s`,
    },
  },
  thresholds: {
    ...emptyThresholds(subMetrics),
    drop_queue_unexpected_admission: [{ threshold: 'count==0', abortOnFail: true }],
    drop_queue_waiter_kept: [],
    drop_queue_waiter_lost: [],
  },
  summaryTrendStats,
  teardownTimeout: '30s',
};

export function setup() {
  checkTarget();
  console.log(`drop=${DROP_ID} waiters=${WAITERS} enter=${ENTER_RATE}/s fill=${FILL_MS}ms settle=${SETTLE_MS}ms `
    + `warmup=${WARM_MS}ms measure=${MEASURE_MS}ms poll=${POLL_S}s rowOffset=${ROW_OFFSET}`);
}

function phaseAt(scenarioStart) {
  const t = Date.now() - scenarioStart - FILL_MS;
  if (t < 0) return 'fill';
  if (t < SETTLE_MS) return 'settle';
  if (t < SETTLE_MS + WARM_MS) return 'warmup';
  return 'measure';
}

export default function () {
  const n = exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  const scenarioStart = exec.scenario.startTime;
  const end = scenarioStart + FILL_MS + AFTER_FILL_MS;
  tracked({}, () => {
    const user = users[n];
    // 대기자는 계정 순서대로 핫 SKU에 나뉜다. 입장 순번은 SKU마다 따로 센다.
    const variantId = variantFor(n);
    let entered = enterQueue(user, variantId, { phase: phaseAt(scenarioStart) });
    // 503 QUEUE_BUSY는 오류가 아니라 Retry-After 뒤 다시 진입한다.
    while (entered.status === 'BUSY') {
      const wait = nextWaitSeconds(entered, POLL_S);
      if (Date.now() + wait * 1000 > end) break;
      sleep(wait);
      entered = enterQueue(user, variantId, { phase: phaseAt(scenarioStart) });
    }
    if (entered.status !== 'WAITING') {
      if (entered.status === 'ADMITTED') admitted.add(1);
      const reason = { ADMITTED: 'admitted', SOLD_OUT: 'sold_out' }[entered.status] || 'enter_failed';
      lost.add(1, { reason });
      return;
    }
    // 조회 오류와 타임아웃은 세고 계속 조회한다. 30초 넘게 끊기면 서버가 대기를 닫고 다음 조회가 404가 된다.
    // 서버가 pollAfterMs를 주면 그 간격을 따르고, 없으면 POLL_SECONDS를 쓴다. BUSY는 Retry-After 뒤 다시 묻는다.
    let last = entered;
    for (;;) {
      const wait = nextWaitSeconds(last, POLL_S);
      if (Date.now() + wait * 1000 > end) break;
      sleep(wait);
      last = pollQueue(user, variantId, { phase: phaseAt(scenarioStart) });
      const status = last.status;
      if (status === 'WAITING' || status === 'ERROR' || status === 'BUSY') continue;
      if (status === 'ADMITTED') admitted.add(1);
      lost.add(1, { reason: status.toLowerCase() });
      return;
    }
    kept.add(1);
  });
}

// 측정 직후 서버가 보는 대기 인원. heartbeat가 살아 있는 WAITING만 센다.
export function teardown() {
  const res = http.get(`${MANAGEMENT_URL}/actuator/prometheus`, { tags: { name: 'teardown' } });
  const match = res.status === 200 && /^drop_queue_waiting\s+([0-9.eE+-]+)$/m.exec(res.body);
  console.log(`server drop_queue_waiting after measure: ${match ? Number(match[1]) : 'unavailable'} (target ${WAITERS})`);
}

export function handleSummary(data) {
  const m = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const measured = m('drop_queue_poll{phase:measure}').count || 0;
  const keptCount = m('drop_queue_waiter_kept').count || 0;
  const lines = [
    `  waiters target ${WAITERS}, kept ${keptCount}, lost ${m('drop_queue_waiter_lost').count || 0}`
      + ` (${LOSS_REASONS.map((r) => `${r} ${m(`drop_queue_waiter_lost{reason:${r}}`).count || 0}`).join(', ')})`,
    `  fixed-N experiment ${keptCount === WAITERS ? 'held' : 'FAILED: waiters were lost or never entered'}`,
    `${latencyLine('eval enter', m('drop_eval_latency{api:enter}'))}  unexpected ${pct(m('drop_unexpected_error{api:enter}').rate)}`,
    ...PHASES.map((p) => `${latencyLine(`eval poll ${p}`, m(`drop_eval_latency{api:poll,phase:${p}}`))}`
      + `  unexpected ${pct(m(`drop_unexpected_error{api:poll,phase:${p}}`).rate)}`),
    `  measure: polls ${measured} in ${MEASURE_MS / 1000}s = ${(measured / (MEASURE_MS / 1000)).toFixed(1)}/s`
      + ` (about ${(WAITERS / POLL_S).toFixed(0)}/s if the server sends no pollAfterMs), waiting replies ${m('drop_queue_poll{result:waiting,phase:measure}').count || 0},`
      + ` timeouts ${m('drop_queue_poll{result:timeout,phase:measure}').count || 0}`,
    `  queue busy (503 QUEUE_BUSY, retried after Retry-After): enter ${m('drop_queue_busy{api:enter}').count || 0}, `
      + `poll in measure ${m('drop_queue_busy{api:poll,phase:measure}').count || 0}`,
  ];
  const out = summarize(data, {
    headline: `drop ${DROP_ID} queue polling, waiters ${WAITERS}, poll every ${POLL_S}s unless the server sends pollAfterMs`,
    conditions: { scenario: 'queue-poll', waiters: WAITERS, enterRate: ENTER_RATE, pollSeconds: POLL_S, fillMs: FILL_MS,
      settleMs: SETTLE_MS, warmMs: WARM_MS, measureMs: MEASURE_MS },
    extraLines: lines,
    extraOutcomes: ['drop_queue_waiter_kept', 'drop_queue_waiter_lost', 'drop_queue_unexpected_admission'],
  });
  return out;
}
