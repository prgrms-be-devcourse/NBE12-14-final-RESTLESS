import type { TicketSeatInfo } from "@/lib/types";

export const sectionName = (name: string) => name.endsWith("구역") ? name : `${name}구역`;

// 가상 샘플의 각 열을 같은 반원에 놓고, 모든 구역에 같은 각도 간격을 적용한다.
// ID, 좌석명, 가격, 상태는 그대로 사용한다.
// 일반 배치는 구역 내부 좌표를 유지하고, 겹치는 구역만 화면에서 아래로 옮긴다.
export function spacedMapSeats(seats: TicketSeatInfo[]): TicketSeatInfo[] {
  if (!isLocalArcSample(seats)) return separateSections(widenTightLayout(seats));
  const centers: Record<string, [number, number]> = { A: [150, 6], B: [90, 8], C: [30, 6] };
  return seats.map((seat) => {
    const [center, count] = centers[seat.sectionLabel.replace(/구역$/, "").trim()];
    const radius = 185 + (Number(seat.rowLabel) - 1) * 40;
    const angle = (center + ((count + 1) / 2 - Number(seat.seatNumber)) * 8) * Math.PI / 180;
    return { ...seat, posX: 450 + radius * Math.cos(angle), posY: 80 + radius * Math.sin(angle) };
  });
}

// 확대한 좌석(16px)이 겹치지 않는 최소 좌석 간격. 성능 시드처럼 좌표가 1칸 단위인 배치는 이 간격으로 넓힌다.
const MIN_PITCH = 20;

/** 같은 열 안의 가로 간격과 열 사이 세로 간격 중 가장 좁은 값. 좌석이 하나뿐이면 Infinity */
export function seatPitch(seats: TicketSeatInfo[]) {
  const minGap = (values: number[]) => {
    const sorted = Array.from(new Set(values)).sort((a, b) => a - b);
    let gap = Infinity;
    for (let i = 1; i < sorted.length; i++) gap = Math.min(gap, sorted[i] - sorted[i - 1]);
    return gap;
  };
  const rows = new Map<string, number[]>();
  const rowYs = new Map<string, number[]>();
  for (const s of seats) {
    const row = `${s.sectionLabel}/${s.rowLabel}`;
    rows.set(row, [...(rows.get(row) ?? []), Number(s.posX)]);
    rowYs.set(s.sectionLabel, [...(rowYs.get(s.sectionLabel) ?? []), Number(s.posY)]);
  }
  return Math.min(...Array.from(rows.values()).concat(Array.from(rowYs.values())).map(minGap));
}

/** 좌석 간격이 좌석 크기보다 좁으면 좌표 전체를 같은 배율로 넓힌다. 구역 배치 모양은 그대로다 */
export function widenTightLayout(seats: TicketSeatInfo[]): TicketSeatInfo[] {
  const pitch = seatPitch(seats);
  if (!Number.isFinite(pitch) || pitch <= 0 || pitch >= MIN_PITCH) return seats;
  const k = MIN_PITCH / pitch;
  return seats.map((s) => ({ ...s, posX: Number(s.posX) * k, posY: Number(s.posY) * k }));
}

const SECTION_TOP = 10;
const SECTION_BOTTOM = 24;
const SECTION_SIDE = 14;
const SECTION_GAP = 16;

function separateSections(seats: TicketSeatInfo[]): TicketSeatInfo[] {
  const grouped = new Map<string, TicketSeatInfo[]>();
  for (const seat of seats) {
    const items = grouped.get(seat.sectionLabel) ?? [];
    items.push(seat);
    grouped.set(seat.sectionLabel, items);
  }
  const sections = [...grouped].map(([name, items]) => ({ name, ...mapBounds(items, 0) }))
    .sort((a, b) => a.y - b.y || a.x - b.x);
  const placed: { left: number; right: number; bottom: number }[] = [];
  const offsets = new Map<string, number>();
  for (const section of sections) {
    const left = section.x - SECTION_SIDE, right = section.x + section.width + SECTION_SIDE;
    let top = section.y - SECTION_TOP;
    for (const previous of placed) {
      if (left < previous.right && right > previous.left) {
        top = Math.max(top, previous.bottom + SECTION_GAP);
      }
    }
    offsets.set(section.name, top + SECTION_TOP - section.y);
    placed.push({ left, right, bottom: top + SECTION_TOP + section.height + SECTION_BOTTOM });
  }
  return seats.map((seat) => ({ ...seat, posY: Number(seat.posY) + (offsets.get(seat.sectionLabel) ?? 0) }));
}

export function sectionLabelPosition(seats: TicketSeatInfo[], arcSample: boolean) {
  const bounds = mapBounds(seats, 0);
  if (!arcSample) return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height + 22 };
  const angles = seats.map((s) => Math.atan2(Number(s.posY) - 80, Number(s.posX) - 450));
  const radius = Math.max(...seats.map((s) => Math.hypot(Number(s.posX) - 450, Number(s.posY) - 80))) + 38;
  const angle = (Math.min(...angles) + Math.max(...angles)) / 2;
  return { x: 450 + radius * Math.cos(angle), y: 80 + radius * Math.sin(angle) };
}

export function mapBounds(seats: TicketSeatInfo[], padding = 28) {
  if (!seats.length) return { x: 0, y: 0, width: 740, height: 420 };
  const xs = seats.map((s) => Number(s.posX));
  const ys = seats.map((s) => Number(s.posY));
  return { x: Math.min(...xs) - padding, y: Math.min(...ys) - padding,
    width: Math.max(...xs) - Math.min(...xs) + padding * 2,
    height: Math.max(...ys) - Math.min(...ys) + padding * 2 };
}

// API의 실제 좌표를 감싸는 외곽선. 사각형, 반원형 모두 같은 좌표계로 표시한다.
export function sectionOutline(seats: TicketSeatInfo[], arcSample = false) {
  // 확대 좌석(16px)의 모서리, 호버/포커스 효과까지 감싸고 여백을 남긴다.
  if (arcSample && seats.length) {
    const angles = seats.map((s) => Math.atan2(Number(s.posY) - 80, Number(s.posX) - 450));
    const radii = seats.map((s) => Math.hypot(Number(s.posX) - 450, Number(s.posY) - 80));
    // 안쪽도 원호로 감싸고 구역 사이 통로를 남긴다.
    const anglePadding = Math.asin(Math.min(1, SECTION_SIDE / Math.min(...radii)));
    const start = Math.min(...angles) - anglePadding;
    const end = Math.max(...angles) + anglePadding;
    const inner = Math.min(...radii) - SECTION_TOP, outer = Math.max(...radii) + SECTION_BOTTOM;
    // 기울어진 양쪽 구역은 안팎 여백 차이가 가로 방향에도 나타난다.
    // 위아래 여백을 유지하면서 좌석과 경계의 가로 중심을 맞춘다.
    const edgeAngles = [start, end, ...[0, Math.PI].filter((angle) => angle >= start && angle <= end)];
    const edgeXs = [inner, outer].flatMap((radius) => edgeAngles.map((angle) => 450 + radius * Math.cos(angle)));
    const seatXs = seats.map((seat) => Number(seat.posX));
    const offsetX = (Math.min(...seatXs) + Math.max(...seatXs) - Math.min(...edgeXs) - Math.max(...edgeXs)) / 2;
    const p = (radius: number, angle: number) => `${450 + offsetX + radius * Math.cos(angle)},${80 + radius * Math.sin(angle)}`;
    return `M${p(outer, start)} A${outer},${outer} 0 0 1 ${p(outer, end)} L${p(inner, end)} A${inner},${inner} 0 0 0 ${p(inner, start)} Z`;
  }
  const points = seats.flatMap((s) => [-SECTION_SIDE, SECTION_SIDE].flatMap((dx) => [-SECTION_TOP, SECTION_BOTTOM].map((dy) =>
    ({ x: Number(s.posX) + dx, y: Number(s.posY) + dy }))));
  points.sort((a, b) => a.x - b.x || a.y - b.y);
  if (!points.length) return "";
  type Point = typeof points[number];
  const cross = (a: Point, b: Point, c: Point) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
  const half = (input: Point[]) => {
    const result: Point[] = [];
    for (const p of input) {
      while (result.length > 1 && cross(result[result.length - 2], result[result.length - 1], p) <= 0) result.pop();
      result.push(p);
    }
    return result.slice(0, -1);
  };
  const hull = [...half(points), ...half([...points].reverse())];
  return `M${hull.map((p) => `${p.x},${p.y}`).join(" L")} Z`;
}

// 회차 ID는 환경마다 달라진다. 로컬 시드의 구역, 열, 번호, 좌표가 전부 일치할 때만 안내한다.
export function isLocalArcSample(seats: TicketSeatInfo[]) {
  if (seats.length !== 100 || new Set(seats.map((s) => `${s.sectionLabel}/${s.rowLabel}/${s.seatNumber}`)).size !== 100) return false;
  const sectors: Record<string, [number, number]> = { A: [168, 6], B: [114, 8], C: [60, 6] };
  return seats.every((s) => {
    const sector = sectors[s.sectionLabel.replace(/구역$/, "").trim()];
    const row = Number(s.rowLabel), number = Number(s.seatNumber);
    if (!sector || !Number.isInteger(row) || row < 1 || row > 5 || !Number.isInteger(number) || number < 1 || number > sector[1]) return false;
    const angle = (sector[0] - (number - 1) * 48 / (sector[1] - 1)) * Math.PI / 180;
    const radius = 145 + (row - 1) * 40;
    return Math.abs(Number(s.posX) - (450 + radius * Math.cos(angle))) < 0.02
      && Math.abs(Number(s.posY) - (80 + radius * Math.sin(angle))) < 0.02;
  });
}
