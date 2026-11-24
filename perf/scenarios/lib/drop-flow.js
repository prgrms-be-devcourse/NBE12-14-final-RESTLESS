// 대기열 진입·상태 조회와 결제 요청. 대표 판매 시나리오와 폴링 비용 시나리오가 함께 쓴다.
// 집계 규칙은 docs/testing/drop-performance-measurement.md를 따른다.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import {
  BASE_URL, DROP_ID, HTTP_TIMEOUT, authHeaders, durationMs, observe, transportKind,
} from './drop-reservation.js';

// 결제 승인은 가짜 PG 지연(기본 300ms)을 포함하므로 재고·대기열보다 길게 둔다.
export const PAY_TIMEOUT = __ENV.PAY_TIMEOUT || '10s';
const PAY_TIMEOUT_MS = durationMs(PAY_TIMEOUT);

// 결과는 태그 result로 나눈다. 요약에 남길 값은 시나리오가 빈 임계값으로 고른다.
const queueEnter = new Counter('drop_queue_enter');
const queuePoll = new Counter('drop_queue_poll');
const payment = new Counter('drop_payment');
// 서버가 대기열 동시 처리 상한을 넘겨 503 DROP_QUEUE_BUSY로 거절한 요청 수. 오류가 아니라 Retry-After 뒤 재시도로 센다.
// 태그 api는 enter, poll.
const queueBusy = new Counter('drop_queue_busy');
// 503 응답의 지연. 빠른 거절이 enter와 poll의 평가 지연에 섞여 포화 때 p95가 좋아 보이지 않게 따로 둔다.
const queueBusyLatency = new Trend('drop_queue_busy_latency', true);
// 회원 한 명의 최종 결과: paid, payment_pending, payment_failed, sold_out, unavailable, queue_closed,
// admission_expired, unfinished(관측 종료까지 대기), error.
const memberResult = new Counter('drop_member_result');
// 회원 도착 후 첫 API의 평가 지연 (off는 재고 점유, on은 대기 진입).
const firstResponse = new Trend('drop_first_response', true);
// 대기 진입 응답부터 ADMITTED를 본 시각까지.
const queueWait = new Trend('drop_queue_wait', true);
// 회원 도착부터 최종 결과까지. 태그 result로 PAID와 품절 등을 나눈다.
const memberTotal = new Trend('drop_member_total', true);

function json(res) {
  try { return res.json() || {}; } catch (e) { return {}; }
}

// 503 DROP_QUEUE_BUSY는 서버가 일부러 일찍 거절한 정상 응답이다. Retry-After(초)를 밀리초로 돌려준다 (기본 1초).
function busyRetryMs(res, body) {
  if (res.status !== 503 || body.code !== 'DROP_QUEUE_BUSY') return null;
  const seconds = Number(res.headers['Retry-After']);
  return seconds > 0 ? seconds * 1000 : 1000;
}

// 다음 요청까지 기다릴 초. BUSY면 Retry-After에 최대 50% 지터를 더해 재시도가 한꺼번에 몰리지 않게 한다.
// 서버가 pollAfterMs를 주면 그 값을 따르고, 없으면 fallbackS(POLL_SECONDS)를 쓴다.
export function nextWaitSeconds(result, fallbackS) {
  if (result.retryAfterMs) return (result.retryAfterMs / 1000) * (1 + Math.random() * 0.5);
  if (result.pollAfterMs > 0) return result.pollAfterMs / 1000;
  return fallbackS;
}

// POST /queue/entries {variantId}. 진입한 SKU의 재고 점유에만 입장 허가가 쓰인다.
// status: WAITING, ADMITTED, SOLD_OUT(그 SKU의 available = 0), NOT_ON_SALE, BUSY(503 DROP_QUEUE_BUSY), ERROR.
export function enterQueue(user, variantId, tags = {}, dropId = DROP_ID) {
  const res = http.post(`${BASE_URL}/api/drops/${dropId}/queue/entries`, JSON.stringify({ variantId }), {
    headers: authHeaders(user),
    tags: { name: 'POST /api/drops/{id}/queue/entries', ...tags },
    timeout: HTTP_TIMEOUT,
  });
  const body = json(res);
  const retryAfterMs = busyRetryMs(res, body);
  let status = 'ERROR';
  if (retryAfterMs) status = 'BUSY';
  else if (res.status === 201 || res.status === 200) status = body.status === 'ADMITTED' ? 'ADMITTED' : 'WAITING';
  else if (res.status === 409 && body.code === 'DROP_NOT_ON_SALE') status = 'NOT_ON_SALE';
  else if (res.status === 409 && body.code === 'DROP_SOLD_OUT') status = 'SOLD_OUT';
  const mismatch = (res.status === 201 || res.status === 200) && body.variantId !== variantId;
  // 한 번만 진입하므로 201 WAITING과 현재 구매 불가(409 DROP_SOLD_OUT)만 정상이다.
  // 200(기존 대기)은 계정 재사용을 뜻한다. 응답 SKU가 요청과 다르면 서버나 스크립트가 틀렸다.
  // BUSY는 평가 지연과 예상 밖 오류율에 넣지 않는다. 별도 카운터와 지연으로만 본다.
  const kind = status === 'BUSY' ? 'busy'
    : observe(res, mismatch || (res.status !== 201 && status !== 'SOLD_OUT'), { api: 'enter', ...tags });
  let result = `existing_${status.toLowerCase()}`;
  if (kind === 'busy') result = 'busy';
  else if (kind !== 'response') result = kind;
  else if (mismatch) result = 'variant_mismatch';
  else if (res.status === 201) result = 'entered';
  else if (status === 'SOLD_OUT') result = 'sold_out';
  else if (status === 'ERROR' || status === 'NOT_ON_SALE') result = `http_${res.status}`;
  queueEnter.add(1, { result, ...tags });
  if (status === 'BUSY') {
    queueBusy.add(1, { api: 'enter', ...tags });
    queueBusyLatency.add(res.timings.duration, { api: 'enter', ...tags });
  }
  return { status, position: body.position, pollAfterMs: body.pollAfterMs, retryAfterMs, res };
}

// GET /queue/me. status: WAITING, ADMITTED, EXPIRED, CONSUMED, UNAVAILABLE, CLOSED(404), BUSY(503 DROP_QUEUE_BUSY), ERROR.
// pollAfterMs는 서버가 WAITING 응답에 싣는 다음 조회 간격이다.
// UNAVAILABLE은 그 SKU가 현재 구매 불가가 되어 대기나 미사용 입장 허가가 끝났다는 뜻이다.
export function pollQueue(user, variantId, tags = {}, dropId = DROP_ID) {
  const res = http.get(`${BASE_URL}/api/drops/${dropId}/queue/me`, {
    headers: authHeaders(user),
    tags: { name: 'GET /api/drops/{id}/queue/me', ...tags },
    timeout: HTTP_TIMEOUT,
  });
  let status = 'ERROR';
  let mismatch = false;
  let pollAfterMs;
  const body = json(res);
  const retryAfterMs = busyRetryMs(res, body);
  if (res.status === 200) {
    status = body.status || 'ERROR';
    mismatch = body.variantId !== variantId;
    pollAfterMs = body.pollAfterMs;
  } else if (res.status === 404) status = 'CLOSED';
  else if (retryAfterMs) status = 'BUSY';
  // 404는 이탈·판매 종료로 닫힌 대기다. 정상 응답이지만 결과로 따로 센다.
  const kind = status === 'BUSY' ? 'busy' : observe(res, status === 'ERROR' || mismatch, { api: 'poll', ...tags });
  let result = status.toLowerCase();
  if (kind !== 'response' && kind !== 'busy') result = kind;
  else if (status === 'ERROR') result = `http_${res.status}`;
  else if (mismatch) result = 'variant_mismatch';
  queuePoll.add(1, { result, ...tags });
  if (status === 'BUSY') {
    queueBusy.add(1, { api: 'poll', ...tags });
    queueBusyLatency.add(res.timings.duration, { api: 'poll', ...tags });
  }
  return { status, pollAfterMs, retryAfterMs };
}

// 결제 준비와 승인. result: paid, payment_pending(202), payment_failed, error.
export function pay(user, reservation, tags = {}, dropId = DROP_ID) {
  const prepared = http.post(`${BASE_URL}/api/payments`,
    JSON.stringify({ payableType: 'DROP', payableId: reservation.reservationId }), {
      headers: authHeaders(user, { 'Idempotency-Key': `perf-pay-${dropId}-${user.id}` }),
      tags: { name: 'POST /api/payments', ...tags },
      timeout: PAY_TIMEOUT,
    });
  const prepareOk = prepared.status === 201 || prepared.status === 200;
  observe(prepared, !prepareOk, { api: 'prepare', ...tags }, PAY_TIMEOUT_MS);
  if (!prepareOk) {
    payment.add(1, { result: transportKind(prepared) !== 'response' ? `prepare_${transportKind(prepared)}` : `prepare_http_${prepared.status}`, ...tags });
    return 'error';
  }
  const body = json(prepared);
  const confirmed = http.post(`${BASE_URL}/api/payments/confirm`,
    JSON.stringify({ paymentKey: `perf-key-${dropId}-${user.id}`, orderId: body.pgOrderId, amount: body.amount }), {
      headers: authHeaders(user),
      tags: { name: 'POST /api/payments/confirm', ...tags },
      timeout: PAY_TIMEOUT,
    });
  const status = confirmed.status === 200 || confirmed.status === 202 ? json(confirmed).status : null;
  let result = 'error';
  if (status === 'APPROVED') result = 'paid';
  else if (confirmed.status === 202) result = 'payment_pending';
  else if (status === 'FAILED') result = 'payment_failed';
  // 정상 가짜 PG에서는 승인만 정상이다. 대기·실패는 장애 주입이 켜졌거나 서버 문제다.
  observe(confirmed, result !== 'paid', { api: 'confirm', ...tags }, PAY_TIMEOUT_MS);
  payment.add(1, { result: transportKind(confirmed) !== 'response' ? `confirm_${transportKind(confirmed)}` : result === 'error' ? `confirm_http_${confirmed.status}` : result, ...tags });
  return result;
}

export function recordFirstResponse(res, tags = {}) {
  const kind = transportKind(res);
  if (kind === 'response') firstResponse.add(res.timings.duration, tags);
  else if (kind === 'timeout') firstResponse.add(durationMs(HTTP_TIMEOUT), tags);
}

export function recordQueueWait(ms, tags = {}) { queueWait.add(ms, tags); }

export function recordMember(result, startedMs, tags = {}) {
  memberResult.add(1, { result, ...tags });
  memberTotal.add(Date.now() - startedMs, { result, ...tags });
}
