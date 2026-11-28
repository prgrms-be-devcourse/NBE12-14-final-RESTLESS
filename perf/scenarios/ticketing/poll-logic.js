import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';

export function runPoll(data, context) {
  const { base, performanceId, users, entry, vus, participants, mode, headers, seatHeaders, encodingMatches, seatGzipResponses, seatEncodingErrors, pollRequests, pollErrors, count, pollLatency, availabilityRequests, availabilityErrors, availabilityLatency, pageEntryLatency, startOffset, entryEndOffset, origin, refreshCount, refreshInterval, refreshRequests, arrival, arrivalRate, arrivalLag, seatCheck } = context;
  const light = seatCheck === 'light';
  // 도착률 모드는 반복 순서로 사용자를 고른다(반복 하나가 사용자 한 명). 아니면 VU 하나가 사용자 한 명이다.
  const index = arrival ? exec.scenario.iterationInTest : exec.vu.idInTest - 1;
  const user = users[index];
  const seatParams = { headers: seatHeaders(user), tags: { name: 'GET seats', phase: 'entry' }, ...(light ? { responseType: 'none' } : {}) };
  if (arrival) arrivalLag.add(Math.max(0, Date.now() - (exec.scenario.startTime + index * 1000 / arrivalRate)));
  // 분산 진입은 자동 갱신이 아니라 최초 요청의 시작 시간만 3초에 분산한다.
  if (!arrival && entry === 'spread') sleep(3 * (exec.vu.idInTest - 1) / (mode === 'mixed' ? participants : vus));
  // page와 mixed 조회자는 화면 진입처럼 좌석과 구매 가능 정보를 함께 조회한다.
  const pageEntry = mode === 'page' || mode === 'mixed';
  let res, pageElapsed, availabilityValid = false;
  startOffset.add(Date.now() - origin, { phase: 'entry' });
  if (pageEntry) {
    const pageStart = Date.now();
    const responses = http.batch([
      ['GET', `${base}/api/performances/${performanceId}/seats`, null, seatParams],
      ['GET', `${base}/api/performances/${performanceId}/booking-availability`, null, { headers: headers(user), tags: { name: 'GET booking availability', phase: 'entry' } }],
    ]);
    pageElapsed = Date.now() - pageStart;
    res = responses[0];
    const availability = responses[1];
    let valid = false; try { const body = availability.json(); valid = availability.status === 200 && ['maximum', 'confirmed', 'reserved', 'remaining'].every(key => Number.isInteger(body[key])) && Object.prototype.hasOwnProperty.call(body, 'activeReservation'); } catch (_) { /* malformed */ }
    availabilityValid = valid;
    availabilityRequests.add(1); availabilityErrors.add(!valid); count(valid ? 'availability_ok' : 'availability_error', !valid); availabilityLatency.add(availability.timings.duration);
  } else {
    res = http.get(`${base}/api/performances/${performanceId}/seats`, seatParams);
  }
  if ((res.headers['Content-Encoding'] || '').toLowerCase() === 'gzip') seatGzipResponses.add(1);
  seatEncodingErrors.add(!encodingMatches(res));
  let seats;
  if (!light) { try { seats = res.json(); } catch (_) { /* malformed */ } }
  const lightLength = Number(res.headers['Content-Length'] || 0);
  const initialOk = light
    ? res.status === 200 && lightLength * 2 >= data.seatBodyLength
    : res.status === 200 && Array.isArray(seats) && seats.length === data.seatCount;
  entryEndOffset.add(Date.now() - origin);
  if (pageEntry) pageEntryLatency.add(pageElapsed, { outcome: initialOk && availabilityValid && encodingMatches(res) ? 'ok' : 'error' });
  pollRequests.add(1); pollErrors.add(!initialOk); count(initialOk ? 'poll_ok' : 'poll_error', !initialOk); pollLatency.add(res.timings.duration, { outcome: initialOk ? 'ok' : 'error', phase: 'entry' });
  if (!initialOk || light) return;
  let ids = seats.map(s => s.seatId);
  for (let i = 0; i < refreshCount; i++) {
    sleep(refreshInterval);
    refreshRequests.add(1);
    const state = http.get(`${base}/api/performances/${performanceId}/seats/status`, { headers: headers(user), tags: { name: 'GET seat states', phase: 'refresh' } });
    let values; try { values = state.json(); } catch (_) { /* malformed */ }
    const received = state.status === 200 && values !== undefined;
    const byId = new Set();
    const matches = Array.isArray(values) && values.length === ids.length && values.every(s => {
      if (!s || !Number.isSafeInteger(s.seatId) || byId.has(s.seatId) || !['AVAILABLE', 'HELD', 'SOLD'].includes(s.status)) return false;
      byId.add(s.seatId);
      return true;
    }) && ids.every(id => byId.has(id));
    let ok = received && matches;
    if (received && !matches) {
      const full = http.get(`${base}/api/performances/${performanceId}/seats`, { headers: seatHeaders(user), tags: { name: 'GET seats', phase: 'refresh_fallback' } });
      let updated; try { updated = full.json(); } catch (_) { /* malformed */ }
      ok = full.status === 200 && Array.isArray(updated) && encodingMatches(full);
      if (ok) ids = updated.map(s => s.seatId);
      seatEncodingErrors.add(!encodingMatches(full));
    }
    pollRequests.add(1); pollErrors.add(!ok); count(ok ? 'refresh_ok' : 'refresh_error', !ok); pollLatency.add(state.timings.duration, { outcome: ok ? 'ok' : 'error', phase: 'refresh' });
  }
}
