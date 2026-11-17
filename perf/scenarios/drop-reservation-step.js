// EXP-01 용량 측정: 도착률을 계단식으로 올려 무릎 지점과 최대 지속 처리량을 찾는다. 사용법은 perf/scenarios/README.md.
//
// 대기열 없이 POST /api/drops/{id}/reservations만 친다. 앱은 restless.drop.queue.required=false로 띄운다.
// 반복마다 users.csv의 다른 행을 쓴다. 한 계정은 테스트 전체에서 한 번만 요청한다.
// 품절 응답만 재지 않도록 총재고가 계획 도착 수보다 커야 한다. 시드는 --scale capacity를 쓴다.
import exec from 'k6/execution';
import {
  DROP_ID, VARIANT_IDS, TOTAL_STOCK, loadUsers, baseThresholds, summaryTrendStats,
  checkTarget, reserve, skipped, summarize, ROW_OFFSET,
} from './lib/drop-reservation.js';

const STEPS = (__ENV.STEPS || '50,100,200,400,800').split(',').map((s) => Number(s.trim()));
const STEP_RAMP = __ENV.STEP_RAMP || '10s';
const STEP_HOLD = __ENV.STEP_HOLD || '2m';
const PRE_VUS = Number(__ENV.PRE_VUS || 200);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);
// 5xx와 연결 오류, 타임아웃 비율이 이 값을 넘으면 멈춘다. 테스트 시작부터의 누적 비율이다. 1이면 멈추지 않는다.
const ABORT_ERROR_RATE = Number(__ENV.ABORT_ERROR_RATE || 0.05);
const ALLOW_SOLD_OUT = __ENV.ALLOW_SOLD_OUT === '1';

if (STEPS.some((r) => !(r > 0))) throw new Error(`STEPS must be positive numbers: ${__ENV.STEPS}`);

function seconds(d) {
  const m = /^(\d+)(s|m)$/.exec(d);
  if (!m) throw new Error(`duration must look like 30s or 2m: ${d}`);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}
const RAMP_S = seconds(STEP_RAMP);
const HOLD_S = seconds(STEP_HOLD);

// 계단마다 RAMP 동안 다음 도착률로 올리고 HOLD 동안 유지한다. 마지막에 5초 동안 내린다.
const stages = [];
let planned = 0;
let prev = 0;
for (const rate of STEPS) {
  stages.push({ target: rate, duration: STEP_RAMP }, { target: rate, duration: STEP_HOLD });
  planned += ((prev + rate) / 2) * RAMP_S + rate * HOLD_S;
  prev = rate;
}
stages.push({ target: 0, duration: '5s' });
planned += (prev / 2) * 5;
const PLANNED = Math.ceil(planned);

if (TOTAL_STOCK - ROW_OFFSET < PLANNED && !ALLOW_SOLD_OUT) {
  throw new Error(`stock ${TOTAL_STOCK} minus ROW_OFFSET ${ROW_OFFSET} < planned arrivals ${PLANNED}: the run would mostly measure sold-out replies. `
    + 'Seed with --scale capacity, lower STEPS or STEP_HOLD, or set ALLOW_SOLD_OUT=1.');
}
const users = loadUsers(PLANNED);

// 지금 시각이 어느 계단인지. 유지 구간은 도착률 값, 오르는 구간은 ramp로 태그한다. 요약은 유지 구간만 본다.
const STEP_S = RAMP_S + HOLD_S;
function stageTag() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const i = Math.floor(elapsed / STEP_S);
  if (i >= STEPS.length) return 'ramp';
  return elapsed - i * STEP_S < RAMP_S ? 'ramp' : String(STEPS[i]);
}

const stepThresholds = {};
for (const rate of STEPS) {
  stepThresholds[`drop_reservation_duration{stage:${rate}}`] = [];
  stepThresholds[`drop_error_rate{stage:${rate}}`] = [];
  stepThresholds[`http_reqs{stage:${rate}}`] = [];
}

export const options = {
  scenarios: {
    drop_step: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages,
    },
  },
  thresholds: {
    ...baseThresholds,
    ...stepThresholds,
    drop_error_rate: [{ threshold: `rate<${ABORT_ERROR_RATE}`, abortOnFail: true, delayAbortEval: '30s' }],
  },
  summaryTrendStats,
};

export function setup() {
  checkTarget();
  console.log(`drop=${DROP_ID} variants=${VARIANT_IDS} stock=${TOTAL_STOCK} steps=${STEPS} ramp=${STEP_RAMP} `
    + `hold=${STEP_HOLD} planned=${PLANNED} accounts=${users.length} rowOffset=${ROW_OFFSET}`);
}

export default function () {
  const n = exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  reserve(users[n], n, { stage: stageTag() });
}

export function handleSummary(data) {
  const values = (key) => (data.metrics[key] ? data.metrics[key].values : {});
  const steps = STEPS.map((rate) => {
    const reqs = values(`http_reqs{stage:${rate}}`).count || 0;
    return {
      targetRate: rate,
      requests: reqs,
      achievedRate: Number((reqs / HOLD_S).toFixed(1)),
      errorRate: values(`drop_error_rate{stage:${rate}}`).rate,
      latency: values(`drop_reservation_duration{stage:${rate}}`),
    };
  });
  const out = summarize(data, {
    headline: `drop ${DROP_ID}, stock ${TOTAL_STOCK}, steps ${STEPS.join('/')}/s (ramp ${STEP_RAMP}, hold ${STEP_HOLD}), planned ${PLANNED}`,
    conditions: { steps: STEPS, stepRamp: STEP_RAMP, stepHold: STEP_HOLD, planned: PLANNED, preVUs: PRE_VUS, maxVUs: MAX_VUS,
      abortErrorRate: ABORT_ERROR_RATE, stepResults: steps },
    extraLatency: STEPS.map((rate) => [`step ${rate}/s`, `drop_reservation_duration{stage:${rate}}`]),
  });
  const table = steps.map((s) => `  step ${String(s.targetRate).padStart(5)}/s  achieved ${String(s.achievedRate).padStart(7)}/s`
    + `  errors ${s.errorRate === undefined ? '-' : (s.errorRate * 100).toFixed(2)}%`);
  out.stdout += `${table.join('\n')}\n`;
  return out;
}
