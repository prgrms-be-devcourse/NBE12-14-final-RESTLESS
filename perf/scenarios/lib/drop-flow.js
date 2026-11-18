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
// 회원 한 명의 최종 결과: paid, payment_pending, payment_failed, sold_out, queue_closed,
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

// POST /queue/entries. status: WAITING, ADMITTED, NOT_ON_SALE, ERROR.
export function enterQueue(user, tags = {}, dropId = DROP_ID) {
  const res = http.post(`${BASE_URL}/api/drops/${dropId}/queue/entries`, null, {
    headers: authHeaders(user),
    tags: { name: 'POST /api/drops/{id}/queue/entries', ...tags },
    timeout: HTTP_TIMEOUT,
  });
  const body = json(res);
  let status = 'ERROR';
  if (res.status === 201 || res.status === 200) status = body.status === 'ADMITTED' ? 'ADMITTED' : 'WAITING';
  else if (res.status === 409 && body.code === 'DROP_NOT_ON_SALE') status = 'NOT_ON_SALE';
  // 한 번만 진입하므로 201 WAITING만 정상이다. 200(기존 대기)은 계정 재사용을 뜻한다.
  const kind = observe(res, res.status !== 201, { api: 'enter', ...tags });
  const result = kind !== 'response' ? kind : res.status === 201 ? 'entered' : status === 'ERROR' ? `http_${res.status}` : `existing_${status.toLowerCase()}`;
  queueEnter.add(1, { result, ...tags });
  return { status, position: body.position, res };
}

// GET /queue/me. status: WAITING, ADMITTED, EXPIRED, CONSUMED, CLOSED(404), ERROR.
export function pollQueue(user, tags = {}, dropId = DROP_ID) {
  const res = http.get(`${BASE_URL}/api/drops/${dropId}/queue/me`, {
    headers: authHeaders(user),
    tags: { name: 'GET /api/drops/{id}/queue/me', ...tags },
    timeout: HTTP_TIMEOUT,
  });
  let status = 'ERROR';
  if (res.status === 200) status = json(res).status || 'ERROR';
  else if (res.status === 404) status = 'CLOSED';
  // 404는 이탈·판매 종료로 닫힌 대기다. 정상 응답이지만 결과로 따로 센다.
  const kind = observe(res, status === 'ERROR', { api: 'poll', ...tags });
  queuePoll.add(1, { result: kind !== 'response' ? kind : status === 'ERROR' ? `http_${res.status}` : status.toLowerCase(), ...tags });
  return status;
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
