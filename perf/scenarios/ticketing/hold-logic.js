import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';

export function runHold(data, context) {
  const { base, performanceId, users, mode, participants, holdParticipants, entry, origin, mixedHold, hotSeats, runId, headers, holdAttempts, holdCompleted, replayCompleted, holdErrors, count, holdLatency, startOffset, arrival, arrivalRate, arrivalLag, arrivalIndex, holdOrdinal } = context;
  if (!arrival && mode === 'mixed' && exec.vu.iterationInScenario > 0) { sleep(1); return; }
  // 도착률 mixed는 도착 순서(arrivalIndex)로 사용자를, 선점자 순번(holdOrdinal)으로 좌석을 고른다.
  const n = arrival ? holdOrdinal : mode === 'mixed' ? exec.vu.idInTest - participants - 1 : exec.scenario.iterationInTest;
  const user = users[arrival ? arrivalIndex : mode === 'mixed' ? participants + n : n];
  if (arrival) arrivalLag.add(Math.max(0, Date.now() - (exec.scenario.startTime + arrivalIndex * 1000 / arrivalRate)));
  // mixed 선점자는 조회자와 같은 방식으로 출발해 두 부하가 겹치게 한다. 분산 진입이면 같은 3초 구간에 고르게 퍼진다.
  if (!arrival && mode === 'mixed' && entry === 'spread') sleep(3 * n / holdParticipants);
  let ids = (mode === 'hot' || (mode === 'mixed' && mixedHold === 'hot')) ? [data.ids[n % hotSeats]] : mode === 'overlap' ? (n % 2 ? [data.ids[1], data.ids[2]] : [data.ids[0], data.ids[1]]) : [data.ids[n]];
  const key = `perf-${runId}-${user.id}`;
  const body = JSON.stringify({ performanceId, seatIds: ids });
  const request = () => http.post(`${base}/api/ticket-reservations`, body, { headers: headers(user, key), tags: { name: 'POST reservation' } });
  holdAttempts.add(1);
  startOffset.add(Date.now() - (origin || exec.scenario.startTime), { phase: 'hold' });
  const res = request();
  holdCompleted.add(1);
  let code = '', reservationId; try { const json = res.json(); code = json.code; reservationId = json.reservationId; } catch (_) { /* malformed */ }
  let outcome = res.status === 201 && reservationId ? 'created' : res.status === 409 && code === 'SEAT_UNAVAILABLE' ? 'unavailable' : res.status === 409 && code === 'SEAT_LOCK_TIMEOUT' ? 'lock_timeout' : res.status === 0 ? 'transport_error' : res.status >= 500 ? 'server_error' : res.status === 401 ? 'unauthorized' : `unexpected_${res.status}`;
  const expected = outcome === 'created' || ((mode === 'hot' || mode === 'overlap' || (mode === 'mixed' && mixedHold === 'hot')) && (outcome === 'unavailable' || outcome === 'lock_timeout'));
  holdErrors.add(!expected); count(outcome, !expected); holdLatency.add(res.timings.duration, { outcome });
  if (mode === 'replay' && outcome === 'created') {
    const again = request(); replayCompleted.add(1); let replayId; try { replayId = again.json('reservationId'); } catch (_) { /* malformed */ }
    const ok = again.status === 201 && replayId === reservationId;
    count(ok ? 'replay_ok' : 'replay_error', !ok); holdLatency.add(again.timings.duration, { outcome: ok ? 'replay_ok' : 'replay_error' });
  }
}
