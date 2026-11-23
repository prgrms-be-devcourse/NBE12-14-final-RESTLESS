import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';

export function runPoll(data, context) {
  const { base, performanceId, users, entry, vus, participants, mode, headers, seatHeaders, encodingMatches, seatGzipResponses, seatEncodingErrors, pollRequests, pollErrors, count, pollLatency, availabilityRequests, availabilityErrors, availabilityLatency, burstStartDelay } = context;
  const user = users[exec.vu.idInTest - 1];
  if (mode === 'burst') {
    const wait = data.burstAt - Date.now();
    if (wait > 0) sleep(wait / 1000);
    burstStartDelay.add(Date.now() - data.burstAt);
  }
  // VU 시작 시점을 3초 구간에 분산한다. 이후 요청 시작 간격은 최소 3초다.
  if (entry === 'spread' && exec.vu.iterationInScenario === 0) sleep(3 * (exec.vu.idInTest - 1) / (mode === 'mixed' ? participants : vus));
  const start = Date.now();
  const phase = exec.vu.iterationInScenario === 0 ? 'entry' : 'poll';
  const statesOnly = phase === 'poll' && mode !== 'burst';
  let res;
  if (mode === 'page' && phase === 'entry') {
    const responses = http.batch([
      ['GET', `${base}/api/performances/${performanceId}/seats`, null, { headers: seatHeaders(user), tags: { name: 'GET seats', phase } }],
      ['GET', `${base}/api/performances/${performanceId}/booking-availability`, null, { headers: headers(user), tags: { name: 'GET booking availability', phase } }],
    ]);
    res = responses[0];
    const availability = responses[1];
    let valid = false;
    try { const body = availability.json(); valid = availability.status === 200 && ['maximum', 'confirmed', 'reserved', 'remaining'].every(key => Number.isInteger(body[key])) && Object.prototype.hasOwnProperty.call(body, 'activeReservation'); } catch (_) { /* malformed */ }
    availabilityRequests.add(1); availabilityErrors.add(!valid); count(valid ? 'availability_ok' : 'availability_error', !valid); availabilityLatency.add(availability.timings.duration);
  } else {
    const path = statesOnly ? 'seats/status' : 'seats';
    res = http.get(`${base}/api/performances/${performanceId}/${path}`, { headers: statesOnly ? headers(user) : seatHeaders(user), tags: { name: statesOnly ? 'GET seat states' : 'GET seats', phase } });
  }
  if (!statesOnly) {
    if ((res.headers['Content-Encoding'] || '').toLowerCase() === 'gzip') seatGzipResponses.add(1);
    seatEncodingErrors.add(!encodingMatches(res));
  }
  let ok = false; try { const body = res.json(); ok = res.status === 200 && Array.isArray(body) && body.length === data.seatCount && (!statesOnly || body.every((s, i) => s.seatId === data.seatIds[i] && ['AVAILABLE', 'HELD', 'SOLD'].includes(s.status))); } catch (_) { /* malformed */ }
  pollRequests.add(1); pollErrors.add(!ok); count(ok ? 'poll_ok' : 'poll_error', !ok); pollLatency.add(res.timings.duration, { outcome: ok ? 'ok' : 'error', phase });
  if (mode !== 'burst') sleep(Math.max(0, (3000 - (Date.now() - start)) / 1000));
}
