import http from 'k6/http';
import exec from 'k6/execution';

function json(res) { try { return res.json(); } catch (_) { return null; } }

// 선점 → checkout(공통 주문 준비) → 결제 준비까지. 결제 승인은 보내지 않는다.
// 승인 전 READY 시도는 결과 미확정 보호를 켜지 않으므로 checkout 5분 뒤 결제 기한 만료가 좌석을 돌려준다.
export function runCheckout(data, context) {
  const { base, performanceId, users, runId, origin, headers, seatHeaders, encodingMatches, count, startOffset, pageEntry,
    pageEntryLatency, holdAttempts, holdCompleted, holdLatency, checkoutLatency, prepareLatency, journeyLatency,
    checkoutCompleted, prepareCompleted } = context;
  const n = exec.scenario.iterationInTest;
  const user = users[n];
  const seatId = data.ids[n];
  if (pageEntry) {
    // 화면 진입이 실패하면 선점으로 넘어가지 않고 실패로 센다.
    startOffset.add(Date.now() - origin, { phase: 'entry' });
    const pageStart = Date.now();
    const [seats, availability] = http.batch([
      ['GET', `${base}/api/performances/${performanceId}/seats`, null, { headers: seatHeaders(user), tags: { name: 'GET seats', phase: 'entry' } }],
      ['GET', `${base}/api/performances/${performanceId}/booking-availability`, null, { headers: headers(user), tags: { name: 'GET booking availability', phase: 'entry' } }],
    ]);
    // page와 같게 두 응답을 받은 직후의 시간을 잰다. 파싱·검증 시간은 넣지 않는다.
    const pageElapsed = Date.now() - pageStart;
    const seatList = seats.status === 200 ? json(seats) : null;
    const seatsOk = Array.isArray(seatList) && seatList.length === data.seatCount && encodingMatches(seats);
    const body = availability.status === 200 ? json(availability) : null;
    const availabilityOk = !!body && ['maximum', 'confirmed', 'reserved', 'remaining'].every(key => Number.isInteger(body[key]))
      && Object.prototype.hasOwnProperty.call(body, 'activeReservation');
    pageEntryLatency.add(pageElapsed, { outcome: seatsOk && availabilityOk ? 'ok' : 'error' });
    if (!seatsOk || !availabilityOk) { count(seatsOk ? 'page_availability_error' : 'page_seats_error', true); return; }
  }
  const journeyStart = Date.now();
  const finish = (outcome, bad) => { count(outcome, bad); journeyLatency.add(Date.now() - journeyStart, { outcome: bad ? 'error' : 'ok' }); };

  // 선점 직전에 기록한다. 화면 진입을 켜도 실제 선점 출발 시각을 본다.
  startOffset.add(Date.now() - origin, { phase: 'hold' });
  holdAttempts.add(1);
  const hold = http.post(`${base}/api/ticket-reservations`, JSON.stringify({ performanceId, seatIds: [seatId] }),
    { headers: headers(user, `perf-${runId}-${user.id}`), tags: { name: 'POST reservation' } });
  holdCompleted.add(1);
  const reservationId = hold.status === 201 ? (json(hold) || {}).reservationId : null;
  holdLatency.add(hold.timings.duration, { outcome: reservationId ? 'created' : 'error' });
  if (!reservationId) return finish(`hold_${hold.status}`, true);

  const checkout = http.post(`${base}/api/ticket-reservations/${reservationId}/checkout`, null,
    { headers: headers(user), tags: { name: 'POST checkout' } });
  checkoutCompleted.add(1);
  const order = checkout.status === 200 ? json(checkout) : null;
  const checkoutOk = !!order && order.reservationId === reservationId && Number.isSafeInteger(order.orderId)
    && Number.isSafeInteger(order.totalAmount) && order.totalAmount > 0 && !!order.paymentExpiresAt;
  const checkoutCode = (json(checkout) || {}).code;
  checkoutLatency.add(checkout.timings.duration, { outcome: checkoutOk ? 'ok' : checkoutCode === 'SEAT_LOCK_TIMEOUT' ? 'lock_timeout' : 'error' });
  if (!checkoutOk) return finish(`checkout_${checkoutCode || checkout.status}`, true);

  const prepare = http.post(`${base}/api/payments`, JSON.stringify({ payableType: 'TICKET', payableId: reservationId }),
    { headers: headers(user, `perf-${runId}-${user.id}-pay`), tags: { name: 'POST payment prepare' } });
  prepareCompleted.add(1);
  const payment = prepare.status === 201 ? json(prepare) : null;
  const prepareOk = !!payment && Number.isSafeInteger(payment.paymentId) && payment.status === 'READY'
    && payment.created === true && payment.amount === order.totalAmount;
  prepareLatency.add(prepare.timings.duration, { outcome: prepareOk ? 'ok' : 'error' });
  if (!prepareOk) return finish(`prepare_${(json(prepare) || {}).code || prepare.status}`, true);
  finish('prepared', false);
}
