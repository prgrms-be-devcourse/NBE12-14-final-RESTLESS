export const won = (n: number) => `${n.toLocaleString("ko-KR")}원`;

export const dateTime = (iso: string) =>
  new Date(iso).toLocaleString("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
  });

export const dateOnly = (iso: string) =>
  new Date(iso).toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric" });

/** 남은 시간 → "2일 03:12:09" / "14:32" */
export function countdown(targetIso: string, now: number) {
  let s = Math.max(0, Math.floor((new Date(targetIso).getTime() - now) / 1000));
  const d = Math.floor(s / 86400);
  s -= d * 86400;
  const h = Math.floor(s / 3600);
  s -= h * 3600;
  const m = Math.floor(s / 60);
  s -= m * 60;
  const p = (n: number) => String(n).padStart(2, "0");
  if (d > 0) return `${d}일 ${p(h)}:${p(m)}:${p(s)}`;
  if (h > 0) return `${p(h)}:${p(m)}:${p(s)}`;
  return `${p(m)}:${p(s)}`;
}

export const msLeft = (iso: string, now: number) => new Date(iso).getTime() - now;
