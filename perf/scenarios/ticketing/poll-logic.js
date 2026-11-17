import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';

export function runPoll(data, context) {
  const { base, performanceId, users, entry, vus, participants, mode, headers, pollRequests, pollErrors, count, pollLatency } = context;
  const user = users[exec.vu.idInTest - 1];
  // VU 시작 시점을 3초 구간에 분산한다. 이후 요청 시작 간격은 최소 3초다.
  if (entry === 'spread' && exec.vu.iterationInScenario === 0) sleep(3 * (exec.vu.idInTest - 1) / (mode === 'mixed' ? participants : vus));
  const start = Date.now();
  const phase = exec.vu.iterationInScenario === 0 ? 'entry' : 'poll';
  const res = http.get(`${base}/api/performances/${performanceId}/seats`, { headers: headers(user), tags: { name: 'GET seats', phase } });
  let ok = false; try { ok = res.status === 200 && Array.isArray(res.json()) && res.json().length === data.seatCount; } catch (_) { /* malformed */ }
  pollRequests.add(1); pollErrors.add(!ok); count(ok ? 'poll_ok' : 'poll_error', !ok); pollLatency.add(res.timings.duration, { outcome: ok ? 'ok' : 'error', phase });
  sleep(Math.max(0, (3000 - (Date.now() - start)) / 1000));
}
