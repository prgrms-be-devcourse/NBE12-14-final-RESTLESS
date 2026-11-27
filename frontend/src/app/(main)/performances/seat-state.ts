import type { TicketSeatInfo, TicketSeatState } from "@/lib/types";

/** 좌석 구성이 달라졌거나 잘못된 응답이면 전체 목록을 다시 받아야 한다. */
export function mergeSeatStates(seats: TicketSeatInfo[], states: TicketSeatState[]): TicketSeatInfo[] | null {
  if (!Array.isArray(states) || states.length !== seats.length) return null;
  const byId = new Map<number, TicketSeatState["status"]>();
  for (const seat of states) {
    if (!seat || !Number.isSafeInteger(seat.seatId) || byId.has(seat.seatId)
        || !["AVAILABLE", "HELD", "SOLD"].includes(seat.status)) return null;
    byId.set(seat.seatId, seat.status);
  }
  if (seats.some((seat) => !byId.has(seat.seatId))) return null;
  return seats.map((seat) => ({ ...seat, status: byId.get(seat.seatId)! }));
}
