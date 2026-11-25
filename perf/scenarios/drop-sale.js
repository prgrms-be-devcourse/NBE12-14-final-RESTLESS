// 대표 판매 측정: 한정 재고에 참여자가 한꺼번에 몰린다. 대기열 off/on을 같은 도착 시간표로 비교한다.
// 계획은 docs/testing/drop-performance-plan.md, 절차는 drop-performance-procedure.md 4절. 사용법은 perf/scenarios/README.md.
//
// off: 도착 즉시 재고 점유. on: 대기 진입 → POLL 간격으로 상태 조회 → 입장 후 재고 점유.
// 점유에 성공하면 가짜 PG 결제까지 잇는다 (PAY=0이면 점유까지만).
// 회원 한 명이 iteration 하나다. 한 계정은 한 번만 참여하고 실패해도 새 회원처럼 다시 들어오지 않는다.
// 앱의 restless.drop.queue.required가 QUEUE와 같아야 한다. 다르면 전제 조건 임계값이 실행을 멈춘다.
// 시드에 워밍업 드롭이 있으면(--scale sale 등) 측정 전에 그 드롭으로 같은 경로를 데운다.
// 워밍업은 핫 드롭 재고와 참여 계정을 쓰지 않고, 요약의 판매 결과에서 빠진다.
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import {
  DROP_ID, VARIANT_IDS, TOTAL_STOCK, manifest, loadUsers, baseThresholds, summaryTrendStats, checkTarget,
  reserve, variantFor, skipped, summarize, tracked, windowTag, durationMs, emptyThresholds, latencyLine, pct, ROW_OFFSET, HOT, RETRY, RETRY_MAX,
  RETRY_MAX_MS, isRetryable,
} from './lib/drop-reservation.js';
import {
  enterQueue, pollQueue, nextWaitSeconds, pay, recordFirstResponse, recordQueueWait, recordMember, PAY_TIMEOUT,
} from './lib/drop-flow.js';

const QUEUE = __ENV.QUEUE || 'off';
if (!['off', 'on'].includes(QUEUE)) throw new Error(`QUEUE must be off or on: ${QUEUE}`);
const PAY = __ENV.PAY !== '0';
// 진입이 응답 없이 끝난 회원(시간 초과, 연결 오류, 5xx)을 상태 조회로 확인한 결과.
// found: 서버에 진입이 있었다. reentered: 404라 다시 진입했다. gave_up: 상한이나 관측 종료까지 확인하지 못했다.
const enterUnknown = new Counter('drop_queue_enter_unknown');

// 참여자 = 총재고 x 경쟁률. 대표 판매는 1,000 x 10, 축소는 500 x 10(PEAK_RATE=500), 200 x 10(PEAK_RATE=200).
const RATIO = Number(__ENV.RATIO || manifest.contention_ratio);
const PARTICIPANTS = Number(__ENV.PARTICIPANTS || TOTAL_STOCK * RATIO);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const RAMP_MS = durationMs(__ENV.RAMP || '5s');
// 0에서 PEAK_RATE까지 오르는 동안 들어오는 인원을 뺀 나머지를 PEAK_RATE로 채운다. 기본값이면 7.5초다.
const RAMP_ARRIVALS = (PEAK_RATE * RAMP_MS) / 2000;
if (RAMP_ARRIVALS > PARTICIPANTS) throw new Error(`ramp alone brings ${RAMP_ARRIVALS} members, more than PARTICIPANTS ${PARTICIPANTS}`);
const HOLD_MS = Math.round(((PARTICIPANTS - RAMP_ARRIVALS) / PEAK_RATE) * 1000);
// k6의 정수 스케줄링으로 마지막 몇 명이 빠지지 않게 유지 구간을 조금 늘린다. 넘친 iteration은 요청 없이 끝난다.
const MARGIN_MS = durationMs(__ENV.MARGIN || '200ms');
const INFLOW_MS = RAMP_MS + HOLD_MS + MARGIN_MS;
// 신규 유입이 끝난 뒤 관측 시간. 대기 중인 회원은 이 시각까지 조회를 계속한다. 절차는 최소 120초, 최대 300초다.
const OBSERVE_MS = durationMs(__ENV.OBSERVE || '300s');
const POLL_S = Number(__ENV.POLL_SECONDS || 2);
const WINDOW_S = Number(__ENV.WINDOW_SECONDS || 10);
// on은 대기 중인 회원마다 VU 하나가 필요하다. 생성기 한계를 넘으면 dropped_iterations로 드러난다.
// 여유 구간의 넘친 iteration이 쓸 VU를 더 둔다.
const MAX_VUS = Number(__ENV.MAX_VUS || (QUEUE === 'on' ? PARTICIPANTS + Math.ceil((PEAK_RATE * MARGIN_MS) / 1000) + 10 : 3000));
const PRE_VUS = Number(__ENV.PRE_VUS || (QUEUE === 'on' ? MAX_VUS : Math.min(MAX_VUS, 1000)));

// 워밍업: 시드의 워밍업 드롭에 WARM_RATE로 WARM 동안 보낸다. 계정은 참여자 뒤 행을 쓴다. WARMUP=0이면 끈다.
const warmSeed = manifest.drop.warmup || null;
const WARMUP = warmSeed && __ENV.WARMUP !== '0'
  ? { dropId: warmSeed.drop_id, variantIds: warmSeed.variants.map((v) => v.variant_id) } : null;
const WARM_RATE = Number(__ENV.WARM_RATE || 30);
const WARM_MS = WARMUP ? durationMs(__ENV.WARM || '60s') : 0;
// 워밍업 회원이 끝나기를 기다리는 시간. 그 뒤에 판매가 시작한다.
const WARM_GAP_MS = WARMUP ? durationMs(__ENV.WARM_GAP || '10s') : 0;
const WARM_COUNT = Math.round((WARM_RATE * WARM_MS) / 1000);
if (WARMUP && WARM_COUNT > warmSeed.total_stock) {
  throw new Error(`warmup arrivals ${WARM_COUNT} exceed warmup drop stock ${warmSeed.total_stock}: lower WARM_RATE or WARM`);
}
if (WARMUP && warmSeed.first_user_row !== PARTICIPANTS) {
  console.warn(`warmup accounts start at row ${PARTICIPANTS}, manifest suggests ${warmSeed.first_user_row}`);
}
const users = loadUsers(PARTICIPANTS + WARM_COUNT);
const WINDOWS = Math.ceil((INFLOW_MS + OBSERVE_MS) / 1000 / WINDOW_S);
const RESULTS = ['paid', 'reserved', 'payment_pending', 'payment_failed', 'sold_out', 'unavailable', 'queue_closed', 'admission_expired', 'unfinished', 'error'];
const APIS = QUEUE === 'on' ? ['enter', 'poll', 'reserve'] : ['reserve'];
if (PAY) APIS.push('prepare', 'confirm');

// 판매 결과는 scenario:sale 태그로만 본다. 워밍업 요청이 섞이지 않는다.
const SALE = 'scenario:sale';
const subMetrics = [
  ...APIS.flatMap((api) => [`drop_eval_latency{api:${api},${SALE}}`, `drop_unexpected_error{api:${api},${SALE}}`]),
  ...RESULTS.flatMap((r) => [`drop_member_result{result:${r},${SALE}}`, `drop_member_total{result:${r},${SALE}}`]),
  `drop_queue_poll{result:waiting,${SALE}}`, `drop_queue_poll{result:closed,${SALE}}`, `drop_queue_poll{result:unavailable,${SALE}}`,
  `drop_queue_enter{result:sold_out,${SALE}}`,
  `drop_queue_busy{api:enter,${SALE}}`, `drop_queue_busy{api:poll,${SALE}}`,
  `drop_queue_busy_latency{api:enter,${SALE}}`, `drop_queue_busy_latency{api:poll,${SALE}}`,
  ...['found', 'reentered', 'gave_up'].map((r) => `drop_queue_enter_unknown{result:${r},${SALE}}`),
  `drop_first_response{${SALE}}`, `drop_queue_wait{${SALE}}`, `iteration_started{${SALE}}`, `iteration_finished{${SALE}}`,
  ...['reserve', 'prepare', 'confirm'].flatMap((api) => [
    `drop_retry_attempts{api:${api},${SALE}}`, `drop_retry_recovered{api:${api},${SALE}}`, `drop_retry_exhausted{api:${api},${SALE}}`, `drop_retry_answered{api:${api},${SALE}}`,
    `drop_retry_eval_latency{api:${api},${SALE}}`,
    `drop_time_to_final{api:${api},retried:false,${SALE}}`, `drop_time_to_final{api:${api},retried:true,${SALE}}`,
  ]),
  `drop_sold_out{${SALE}}`, 'drop_member_result{scenario:warmup}', 'drop_member_result{result:paid,scenario:warmup}',
];
// 창별 구매 경로 지연과 조회 지연. 요청은 시작한 창에 귀속한다. 정확한 분석은 --out 원자료로 한다.
for (let w = 0; w < WINDOWS; w++) {
  subMetrics.push(`drop_eval_latency{api:reserve,win:${w}}`, `drop_unexpected_error{api:reserve,win:${w}}`);
  if (QUEUE === 'on') subMetrics.push(`drop_eval_latency{api:poll,win:${w}}`);
}

// 핫 드롭의 신규 점유만 재고와 대조한다. 워밍업 드롭의 점유는 뺀다.
const thresholds = { ...baseThresholds, ...emptyThresholds(subMetrics) };
delete thresholds.drop_reservation_created;
thresholds[`drop_reservation_created{${SALE}}`] = [`count<=${TOTAL_STOCK}`];

export const options = {
  scenarios: {
    ...(WARMUP ? {
      warmup: {
        executor: 'constant-arrival-rate',
        exec: 'warmup',
        rate: WARM_RATE,
        timeUnit: '1s',
        duration: `${WARM_MS}ms`,
        preAllocatedVUs: Math.min(PRE_VUS, 200),
        maxVUs: Math.max(200, WARM_COUNT),
        gracefulStop: `${Math.ceil(WARM_GAP_MS / 1000)}s`,
      },
    } : {}),
    sale: {
      executor: 'ramping-arrival-rate',
      exec: 'sale',
      startTime: `${WARM_MS + WARM_GAP_MS}ms`,
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: PEAK_RATE, duration: `${RAMP_MS}ms` },
        { target: PEAK_RATE, duration: `${HOLD_MS + MARGIN_MS}ms` },
      ],
      // 유입이 끝나도 대기 중인 회원의 iteration은 관측 종료까지 이어진다.
      gracefulStop: `${Math.ceil(OBSERVE_MS / 1000) + 30}s`,
    },
  },
  // off인데 대기열이 켜져 있으면 baseThresholds의 drop_admission_required가 멈춘다.
  // on인데 대기열이 꺼져 있어도 요청은 성공하므로 setup에서 따로 알린다.
  thresholds,
  summaryTrendStats,
};

export function setup() {
  checkTarget();
  console.log(`drop=${DROP_ID} variants=${VARIANT_IDS} stock=${TOTAL_STOCK} participants=${PARTICIPANTS} queue=${QUEUE} pay=${PAY} `
    + `peak=${PEAK_RATE}/s ramp=${RAMP_MS}ms hold=${HOLD_MS}ms margin=${MARGIN_MS}ms observe=${OBSERVE_MS}ms poll=${POLL_S}s(fallback when pollAfterMs is absent) `
    + `maxVUs=${MAX_VUS} rowOffset=${ROW_OFFSET} `
    + (WARMUP ? `warmup=drop ${WARMUP.dropId} ${WARM_RATE}/s x ${WARM_MS}ms (${WARM_COUNT} accounts from row ${PARTICIPANTS}) gap=${WARM_GAP_MS}ms` : 'warmup=none'));
  if (QUEUE === 'on') console.log('queue=on: the app must run with restless.drop.queue.required=true and the admission scheduler enabled');
}

export function sale() {
  const n = exec.scenario.iterationInTest;
  if (n >= PARTICIPANTS) {
    skipped.add(1);
    return;
  }
  const scenarioStart = exec.scenario.startTime;
  const deadline = scenarioStart + INFLOW_MS + OBSERVE_MS;
  const tag = () => ({ win: windowTag(scenarioStart, WINDOW_S) });
  tracked({}, () => member(users[n], n, deadline, tag, HOT));
}

// 워밍업 드롭에 같은 흐름을 보낸다. 계정은 참여자 뒤 행이고 창 태그는 warmup이다.
export function warmup() {
  const n = exec.scenario.iterationInTest;
  if (n >= WARM_COUNT) {
    skipped.add(1);
    return;
  }
  const deadline = exec.scenario.startTime + WARM_MS + WARM_GAP_MS;
  const tag = () => ({ win: 'warmup' });
  tracked({ win: 'warmup' }, () => member(users[PARTICIPANTS + n], n, deadline, tag, WARMUP));
}

function member(user, n, deadline, tag, target) {
  const startedMs = Date.now();
  if (QUEUE === 'on') {
    // 진입, 조회, 점유가 같은 SKU를 쓴다. 입장 허가는 진입한 SKU에만 통한다.
    const variantId = variantFor(n, target);
    let entered = enterQueue(user, variantId, tag(), target.dropId);
    // 503 거절은 첫 응답 지연에 넣지 않는다. 아래 busy 지연으로만 본다.
    if (entered.status !== 'BUSY') recordFirstResponse(entered.res);
    // 서버가 503 DROP_QUEUE_BUSY로 일찍 거절하면 오류가 아니라 Retry-After 뒤 다시 진입한다.
    // 진입이 응답 없이 끝나면 서버에 진입이 커밋됐을 수 있다. 실제 화면처럼 상태 조회로 확인하고, 404면 다시 진입한다.
    // 확인하지 않고 끝내면 서버에 남은 진입이 입장 허가를 받아 재고를 허가 기한 동안 잡는다 (10회차, #421).
    let unknownSince = null;
    for (;;) {
      while (entered.status === 'BUSY') {
        const wait = nextWaitSeconds(entered, POLL_S);
        if (Date.now() + wait * 1000 > deadline) return recordMember('unfinished', startedMs);
        sleep(wait);
        entered = enterQueue(user, variantId, tag(), target.dropId);
      }
      if (entered.status !== 'ERROR' || !RETRY || !isRetryable(entered.res)) break;
      if (unknownSince === null) unknownSince = Date.now();
      const limitMs = Math.min(deadline, unknownSince + RETRY_MAX_MS);
      let probe = { status: 'ERROR' };
      while (probe.status === 'ERROR' || probe.status === 'BUSY') {
        const wait = nextWaitSeconds(probe, POLL_S);
        if (Date.now() + wait * 1000 > limitMs) {
          enterUnknown.add(1, { result: 'gave_up', ...tag() });
          return recordMember('error', startedMs);
        }
        sleep(wait);
        probe = pollQueue(user, variantId, tag(), target.dropId);
      }
      if (probe.status === 'CLOSED') {
        enterUnknown.add(1, { result: 'reentered', ...tag() });
        entered = enterQueue(user, variantId, tag(), target.dropId);
        continue;
      }
      enterUnknown.add(1, { result: 'found', ...tag() });
      entered = probe;
      break;
    }
    // 그 SKU가 이미 현재 구매 불가면 진입부터 거절된다. 점유의 품절과 같은 결과로 센다.
    if (entered.status === 'SOLD_OUT') return recordMember('sold_out', startedMs);
    if (entered.status === 'ERROR' || entered.status === 'NOT_ON_SALE') return recordMember('error', startedMs);
    let status = entered.status;
    const enteredMs = Date.now();
    // 조회 오류는 세고 계속 조회한다. 실제 화면도 다음 주기에 다시 묻는다.
    // 서버가 pollAfterMs를 주면 그 간격을 따르고, 없으면 POLL_SECONDS를 쓴다. 503 DROP_QUEUE_BUSY는 Retry-After 뒤 다시 묻는다.
    let last = entered;
    while (status === 'WAITING' || status === 'ERROR' || status === 'BUSY') {
      const wait = nextWaitSeconds(last, POLL_S);
      if (Date.now() + wait * 1000 > deadline) return recordMember('unfinished', startedMs);
      sleep(wait);
      last = pollQueue(user, variantId, tag(), target.dropId);
      status = last.status;
    }
    if (status === 'CLOSED') return recordMember('queue_closed', startedMs);
    // 재고가 0이 되어 입장 허가 작업이 대기를 끝냈다. 최종 매진은 아니라서 품절과 나눠 센다.
    if (status === 'UNAVAILABLE') return recordMember('unavailable', startedMs);
    if (status !== 'ADMITTED') return recordMember('admission_expired', startedMs);
    recordQueueWait(Date.now() - enteredMs);
  }

  const reserved = reserve(user, n, tag(), target, deadline);
  if (QUEUE === 'off') recordFirstResponse(reserved.res);
  if (reserved.outcome === 'sold_out') return recordMember('sold_out', startedMs);
  if (reserved.outcome !== 'created' || !reserved.body) return recordMember('error', startedMs);
  if (!PAY) return recordMember('reserved', startedMs);
  const paid = pay(user, reserved.body, tag(), target.dropId, deadline);
  return recordMember(paid, startedMs);
}

export function handleSummary(data) {
  const m = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const lines = [
    ...(WARMUP ? [`  warmup on drop ${WARMUP.dropId}: ${m('drop_member_result{scenario:warmup}').count || 0} members of ${WARM_COUNT} planned, `
      + `${m('drop_member_result{result:paid,scenario:warmup}').count || 0} paid. The counters above include warmup; the lines below are the sale only.`] : []),
    `  sale: started ${m(`iteration_started{${SALE}}`).count || 0}, finished ${m(`iteration_finished{${SALE}}`).count || 0}, `
      + `created ${m(`drop_reservation_created{${SALE}}`).count || 0}, sold out ${m(`drop_sold_out{${SALE}}`).count || 0}`,
    `  member results (of ${PARTICIPANTS} planned):`,
    ...RESULTS.map((r) => `    ${r.padEnd(20)} ${m(`drop_member_result{result:${r},${SALE}}`).count || 0}`),
    latencyLine('first response', m(`drop_first_response{${SALE}}`)),
    ...APIS.map((api) => `${latencyLine(`eval ${api}`, m(`drop_eval_latency{api:${api},${SALE}}`))}  unexpected ${pct(m(`drop_unexpected_error{api:${api},${SALE}}`).rate)}`),
    ...(QUEUE === 'on' ? [
      latencyLine('queue wait', m(`drop_queue_wait{${SALE}}`)),
      `  polls waiting ${m(`drop_queue_poll{result:waiting,${SALE}}`).count || 0}, closed ${m(`drop_queue_poll{result:closed,${SALE}}`).count || 0}, `
        + `unavailable ${m(`drop_queue_poll{result:unavailable,${SALE}}`).count || 0}; enter rejected sold out ${m(`drop_queue_enter{result:sold_out,${SALE}}`).count || 0}`,
      `  queue busy (503 DROP_QUEUE_BUSY, retried after Retry-After): enter ${m(`drop_queue_busy{api:enter,${SALE}}`).count || 0}, `
        + `poll ${m(`drop_queue_busy{api:poll,${SALE}}`).count || 0}`,
      `  enter without response, checked by poll: found ${m(`drop_queue_enter_unknown{result:found,${SALE}}`).count || 0}, `
        + `reentered after 404 ${m(`drop_queue_enter_unknown{result:reentered,${SALE}}`).count || 0}, `
        + `gave up ${m(`drop_queue_enter_unknown{result:gave_up,${SALE}}`).count || 0}`,
      latencyLine('busy enter (503 only)', m(`drop_queue_busy_latency{api:enter,${SALE}}`)),
      latencyLine('busy poll (503 only)', m(`drop_queue_busy_latency{api:poll,${SALE}}`)),
    ] : []),
    `  retry (${RETRY ? `on, per member up to ${RETRY_MAX}` : 'off'}): same Idempotency-Key for timeouts, connection errors and 5xx. `
      + 'First-attempt latency stays in eval lines above; retry responses and time to final success are listed here.',
    ...['reserve', 'prepare', 'confirm'].flatMap((api) => {
      const c = (name) => m(`${name}{api:${api},${SALE}}`).count || 0;
      return [
        `    ${api.padEnd(8)} retry requests ${c('drop_retry_attempts')}, recovered ${c('drop_retry_recovered')}, still failed after retry ${c('drop_retry_exhausted')}, answered 4xx ${c('drop_retry_answered')}`,
        latencyLine(`${api} retry response`, m(`drop_retry_eval_latency{api:${api},${SALE}}`)),
        latencyLine(`${api} final, first try`, m(`drop_time_to_final{api:${api},retried:false,${SALE}}`)),
        latencyLine(`${api} final, retried`, m(`drop_time_to_final{api:${api},retried:true,${SALE}}`)),
      ];
    }),
    latencyLine('arrival to paid', m(`drop_member_total{result:paid,${SALE}}`)),
    latencyLine('arrival to sold out', m(`drop_member_total{result:sold_out,${SALE}}`)),
    `  windows of ${WINDOW_S}s (reserve eval latency, requests attributed to their start window):`,
  ];
  for (let w = 0; w < WINDOWS; w++) {
    const v = m(`drop_eval_latency{api:reserve,win:${w}}`);
    if (!v.count) continue;
    const poll = QUEUE === 'on' ? m(`drop_eval_latency{api:poll,win:${w}}`) : {};
    lines.push(`    win ${String(w).padStart(2)}  reserve n ${String(v.count).padStart(6)} p95 ${fmtMs(v['p(95)'])} p99 ${fmtMs(v['p(99)'])}`
      + ` unexpected ${pct(m(`drop_unexpected_error{api:reserve,win:${w}}`).rate)}`
      + (poll.count ? `  poll n ${String(poll.count).padStart(6)} p95 ${fmtMs(poll['p(95)'])} p99 ${fmtMs(poll['p(99)'])}` : ''));
  }
  return summarize(data, {
    headline: `drop ${DROP_ID} sale, queue ${QUEUE}, stock ${TOTAL_STOCK}, participants ${PARTICIPANTS}, peak ${PEAK_RATE}/s`,
    conditions: { scenario: 'sale', queue: QUEUE, pay: PAY, participants: PARTICIPANTS, ratio: RATIO, peakRate: PEAK_RATE,
      rampMs: RAMP_MS, holdMs: HOLD_MS, marginMs: MARGIN_MS, observeMs: OBSERVE_MS, pollSeconds: POLL_S, retry: RETRY, retryMax: RETRY_MAX, windowSeconds: WINDOW_S,
      payTimeout: PAY_TIMEOUT, preVUs: PRE_VUS, maxVUs: MAX_VUS,
      warmup: WARMUP ? { dropId: WARMUP.dropId, rate: WARM_RATE, ms: WARM_MS, gapMs: WARM_GAP_MS, accounts: WARM_COUNT } : null },
    extraLines: lines,
  });
}

function fmtMs(v) { return v === undefined ? '-' : `${v.toFixed(0)}ms`; }
