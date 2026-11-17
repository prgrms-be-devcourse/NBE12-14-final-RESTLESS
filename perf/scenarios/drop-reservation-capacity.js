// 보조 측정 B: 성공 점유 경로의 용량. MODE=ramp는 연속 증가 탐색(B1), MODE=hold는 고정 도착률 유지(B2)다.
// 계획은 docs/testing/drop-performance-plan.md, 절차는 drop-performance-procedure.md 4절. 사용법은 perf/scenarios/README.md.
//
// 대기열 없이 POST /api/drops/{id}/reservations만 친다. 앱은 restless.drop.queue.required=false,
// 점유 TTL은 실행 시간보다 길게(RESTLESS_DROP_RESERVATION_TTL=PT30M) 띄운다. 시드는 --scale capacity다.
// 워밍업과 측정은 별도 시나리오다. 워밍업은 users.csv 앞 WARM_RATE x WARM 행을, 측정은 그 뒤 행을 쓴다.
// 시나리오마다 iterationInTest가 따로 세어지므로 고정 오프셋으로 계정이 겹치지 않게 한다.
import exec from 'k6/execution';
import {
  DROP_ID, VARIANT_IDS, TOTAL_STOCK, loadUsers, baseThresholds, summaryTrendStats, checkTarget, reserve, skipped,
  summarize, tracked, windowTag, durationMs, emptyThresholds, pct, ROW_OFFSET,
} from './lib/drop-reservation.js';

const MODE = __ENV.MODE || 'ramp';
if (!['ramp', 'hold'].includes(MODE)) throw new Error(`MODE must be ramp or hold: ${MODE}`);
const seconds = (name, fallback) => {
  const ms = durationMs(__ENV[name] || fallback);
  if (ms % 1000) throw new Error(`${name} must be whole seconds`);
  return ms / 1000;
};
const WARM_RATE = Number(__ENV.WARM_RATE || 30);
const WARM_S = seconds('WARM', '60s');
const WINDOW_S = Number(__ENV.WINDOW_SECONDS || 30);
const PRE_VUS = Number(__ENV.PRE_VUS || 300);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);
// 예상 밖 오류의 누적 비율이 이 값을 넘으면 멈춘다. 창 기준 중단은 k6가 실행 중에 판정하지 못하므로 사람이 본다.
const ABORT_ERROR_RATE = Number(__ENV.ABORT_ERROR_RATE || 0.05);
const ALLOW_SOLD_OUT = __ENV.ALLOW_SOLD_OUT === '1';

// 측정 구간: [이름, 시작 도착률, 끝 도착률, 초]. 도착률은 구간 안에서 선형으로 바뀐다.
let phases;
if (MODE === 'ramp') {
  const from = Number(__ENV.FROM || 50), to = Number(__ENV.TO || 350), tail = Number(__ENV.TAIL_RATE || 30);
  phases = [
    ['ramp', from, to, seconds('RAMP', '480s')],
    ['down', to, tail, seconds('DOWN', '30s')],
    ['tail', tail, tail, seconds('TAIL', '90s')],
  ];
} else {
  const rate = Number(__ENV.RATE);
  if (!(rate > 0)) throw new Error('MODE=hold needs RATE (arrivals per second)');
  phases = [['hold', rate, rate, seconds('HOLD', '180s')]];
}
if (phases.some(([, a, b, s]) => !(a > 0 && b > 0 && s > 0))) throw new Error('rates and durations must be positive');

const WARM_COUNT = WARM_RATE * WARM_S;
const MAIN_S = phases.reduce((sum, [, , , s]) => sum + s, 0);
// 계획 도착 수 P: 구간마다 평균 도착률 x 초.
const MAIN_PLANNED = Math.ceil(phases.reduce((sum, [, a, b, s]) => sum + ((a + b) / 2) * s, 0));
const PLANNED = WARM_COUNT + MAIN_PLANNED;
// k6의 정수 스케줄링으로 계획보다 조금 더 시작할 수 있어 계정을 여유 있게 읽는다. 넘치면 요청 없이 끝난다.
const SPARE = Number(__ENV.SPARE_ACCOUNTS || 50);

if (TOTAL_STOCK - ROW_OFFSET < PLANNED && !ALLOW_SOLD_OUT) {
  throw new Error(`stock ${TOTAL_STOCK} minus ROW_OFFSET ${ROW_OFFSET} < planned arrivals ${PLANNED}: the run would measure sold-out replies. `
    + 'Seed with --scale capacity, lower the rates, or set ALLOW_SOLD_OUT=1.');
}
const users = loadUsers(PLANNED + SPARE);

// 측정 시작 뒤 t초의 계획 도착률과 구간 이름.
function planAt(t) {
  let start = 0;
  for (const [name, a, b, s] of phases) {
    if (t < start + s) return { name, rate: a + ((b - a) * (t - start)) / s };
    start += s;
  }
  return { name: phases[phases.length - 1][0], rate: phases[phases.length - 1][2] };
}
// 창 하나의 계획 도착 수.
function plannedIn(w) {
  let total = 0;
  for (let t = w * WINDOW_S; t < Math.min((w + 1) * WINDOW_S, MAIN_S); t++) total += (planAt(t).rate + planAt(t + 1 - 1e-9).rate) / 2;
  return total;
}
const WINDOWS = Math.ceil(MAIN_S / WINDOW_S);

const subMetrics = ['iteration_started{scenario:main}', 'iteration_finished{scenario:main}',
  'drop_reservation_created{scenario:main}', 'drop_eval_latency{api:reserve,scenario:main}',
  'drop_unexpected_error{api:reserve,scenario:main}', 'drop_reservation_duration{outcome:created,scenario:main}',
  'drop_eval_latency{api:reserve,scenario:warmup}', 'drop_timeout{scenario:main}', 'drop_transport_error{scenario:main}'];
for (let w = 0; w < WINDOWS; w++) {
  subMetrics.push(`iteration_started{win:${w}}`, `drop_reservation_created{win:${w}}`,
    `drop_eval_latency{api:reserve,win:${w}}`, `drop_unexpected_error{api:reserve,win:${w}}`);
}

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate: WARM_RATE,
      timeUnit: '1s',
      duration: `${WARM_S}s`,
      preAllocatedVUs: Math.min(PRE_VUS, 100),
      maxVUs: MAX_VUS,
      gracefulStop: '10s',
    },
    main: {
      executor: 'ramping-arrival-rate',
      exec: 'main',
      // 워밍업이 끝난 시각에 측정 도착률로 바로 시작한다. 0초 stage를 쓰지 않는다.
      startTime: `${WARM_S}s`,
      startRate: phases[0][1],
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: phases.map(([, , b, s]) => ({ target: b, duration: `${s}s` })),
      gracefulStop: '30s',
    },
  },
  thresholds: {
    ...baseThresholds,
    ...emptyThresholds(subMetrics),
    'drop_unexpected_error{api:reserve}': [{ threshold: `rate<${ABORT_ERROR_RATE}`, abortOnFail: true, delayAbortEval: '30s' }],
  },
  summaryTrendStats,
};

export function setup() {
  checkTarget();
  console.log(`drop=${DROP_ID} variants=${VARIANT_IDS} stock=${TOTAL_STOCK} mode=${MODE} `
    + `phases=${phases.map(([n, a, b, s]) => `${n}:${a}->${b}/s x ${s}s`).join(',')} warmup=${WARM_RATE}/s x ${WARM_S}s `
    + `planned=${PLANNED} (warmup ${WARM_COUNT} + main ${MAIN_PLANNED}) accounts=${users.length} rowOffset=${ROW_OFFSET}`);
}

// 워밍업은 CSV 0..WARM_COUNT-1행만 쓴다. 판정에서 빼지만 계정과 재고는 쓴다.
export function warmup() {
  const n = exec.scenario.iterationInTest;
  if (n >= WARM_COUNT) {
    skipped.add(1);
    return;
  }
  tracked({ win: 'warmup' }, () => reserve(users[n], n, { win: 'warmup' }));
}

// 측정은 WARM_COUNT행부터 쓴다.
export function main() {
  const n = WARM_COUNT + exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  const scenarioStart = exec.scenario.startTime;
  const tags = { win: windowTag(scenarioStart, WINDOW_S), phase: planAt((Date.now() - scenarioStart) / 1000).name };
  tracked(tags, () => reserve(users[n], n, tags));
}

export function handleSummary(data) {
  const m = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const ms = (v) => (v === undefined ? '-' : `${v.toFixed(0)}ms`);
  const windows = [];
  for (let w = 0; w < WINDOWS; w++) {
    const from = planAt(w * WINDOW_S), to = planAt(Math.min((w + 1) * WINDOW_S, MAIN_S) - 1e-9);
    const eval_ = m(`drop_eval_latency{api:reserve,win:${w}}`);
    const planned = plannedIn(w);
    const createdCount = m(`drop_reservation_created{win:${w}}`).count || 0;
    windows.push({ window: w, phase: from.name, fromRate: Number(from.rate.toFixed(1)), toRate: Number(to.rate.toFixed(1)),
      planned: Number(planned.toFixed(1)), started: m(`iteration_started{win:${w}}`).count || 0, created: createdCount,
      // 신규 성공률의 분모는 계획 도착 수 P다. 미전송과 미완료도 분모에 남는다.
      successOfPlanned: planned ? createdCount / planned : null,
      evalP95: eval_['p(95)'], evalP99: eval_['p(99)'], unexpectedRate: m(`drop_unexpected_error{api:reserve,win:${w}}`).rate });
  }
  const started = m('iteration_started{scenario:main}').count || 0;
  const finished = m('iteration_finished{scenario:main}').count || 0;
  const createdMain = m('drop_reservation_created{scenario:main}').count || 0;
  const overall = m('drop_eval_latency{api:reserve,scenario:main}');
  const lines = [
    `  main: planned P ${MAIN_PLANNED}, started S ${started}, finished F ${finished}, unfinished I ${started - finished}, `
      + `dropped D (whole run) ${m('dropped_iterations').count || 0}`,
    `  main: created ${createdMain} = ${pct(createdMain / MAIN_PLANNED)} of planned, eval p95 ${ms(overall['p(95)'])} p99 ${ms(overall['p(99)'])}, `
      + `unexpected ${pct(m('drop_unexpected_error{api:reserve,scenario:main}').rate)}, timeouts ${m('drop_timeout{scenario:main}').count || 0}, `
      + `transport errors ${m('drop_transport_error{scenario:main}').count || 0}`,
    `  windows of ${WINDOW_S}s (requests attributed to their start window; success = created / planned):`,
    ...windows.map((x) => `    win ${String(x.window).padStart(2)} ${x.phase.padEnd(5)} ${String(x.fromRate).padStart(6)}->${String(x.toRate).padEnd(6)}/s`
      + `  planned ${String(x.planned).padStart(8)} started ${String(x.started).padStart(6)} created ${String(x.created).padStart(6)}`
      + ` (${pct(x.successOfPlanned)})  p95 ${ms(x.evalP95)} p99 ${ms(x.evalP99)}  unexpected ${pct(x.unexpectedRate)}`),
  ];
  return summarize(data, {
    headline: `drop ${DROP_ID} capacity ${MODE}, stock ${TOTAL_STOCK}, planned ${PLANNED}`,
    conditions: { scenario: 'capacity', mode: MODE, phases: phases.map(([name, fromRate, toRate, s]) => ({ name, fromRate, toRate, seconds: s })),
      warmRate: WARM_RATE, warmSeconds: WARM_S, warmCount: WARM_COUNT, mainPlanned: MAIN_PLANNED, planned: PLANNED,
      windowSeconds: WINDOW_S, preVUs: PRE_VUS, maxVUs: MAX_VUS, abortErrorRate: ABORT_ERROR_RATE, windows },
    extraLines: lines,
  });
}
