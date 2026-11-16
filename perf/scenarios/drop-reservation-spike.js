// EXP-01 쓰기 기준선: 판매 시작 순간 핫 드롭 재고 점유 스파이크. 사용법은 perf/scenarios/README.md.
//
// 대기열 없이 POST /api/drops/{id}/reservations만 친다. 앱은 restless.drop.queue.required=false로 띄운다.
// 반복마다 users.csv의 다른 행을 쓴다. 한 계정은 테스트 전체에서 한 번만 요청한다.
import exec from 'k6/execution';
import {
  DROP_ID, VARIANT_IDS, TOTAL_STOCK, manifest, loadUsers, baseThresholds, summaryTrendStats,
  checkTarget, reserve, skipped, summarize,
} from './lib/drop-reservation.js';

// 경쟁률 N:1 → 참여자 = 총재고 x N. CSV 앞에서부터 그만큼만 쓴다.
const RATIO = Number(__ENV.RATIO || manifest.contention_ratio);
const PARTICIPANTS = Number(__ENV.PARTICIPANTS || TOTAL_STOCK * RATIO);
const users = loadUsers(PARTICIPANTS);

// 부하 모델: 판매 시작 순간 몰리는 스파이크. 도착률(초당 반복)을 짧게 올려 유지한 뒤 내린다.
// 총 도착 수가 참여자보다 많으면 남는 반복은 요청 없이 끝난다 (skipped_no_account).
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const RAMP = __ENV.RAMP || '5s';
const HOLD = __ENV.HOLD || '20s';
const PRE_VUS = Number(__ENV.PRE_VUS || 500);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);

export const options = {
  scenarios: {
    drop_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: PEAK_RATE, duration: RAMP },
        { target: PEAK_RATE, duration: HOLD },
        { target: 0, duration: '5s' },
      ],
    },
  },
  thresholds: baseThresholds,
  summaryTrendStats,
};

export function setup() {
  checkTarget();
  console.log(`drop=${DROP_ID} variants=${VARIANT_IDS} stock=${TOTAL_STOCK} participants=${PARTICIPANTS} peak=${PEAK_RATE}/s`);
}

export default function () {
  const n = exec.scenario.iterationInTest;
  if (n >= users.length) {
    skipped.add(1);
    return;
  }
  reserve(users[n], n);
}

export function handleSummary(data) {
  return summarize(data, {
    headline: `drop ${DROP_ID}, stock ${TOTAL_STOCK}, participants ${PARTICIPANTS}, peak ${PEAK_RATE}/s (ramp ${RAMP}, hold ${HOLD})`,
    conditions: { participants: PARTICIPANTS, ratio: RATIO, peakRate: PEAK_RATE, ramp: RAMP, hold: HOLD, preVUs: PRE_VUS, maxVUs: MAX_VUS },
  });
}
