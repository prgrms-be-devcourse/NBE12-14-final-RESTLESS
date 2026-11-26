/** 운영 화면 폼 공용 도구. datetime-local 입력은 브라우저 시간대 기준 "YYYY-MM-DDTHH:mm" 문자열이다 */

/** 지금부터 ms 뒤를 분 단위로 자른 datetime-local 값 */
export const local = (ms: number) => {
  const d = new Date(Date.now() + ms);
  d.setSeconds(0, 0);
  return toLocal(d.getTime());
};
export const iso = (v: string) => new Date(v).toISOString();
export const toLocal = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60000).toISOString().slice(0, 16);
/** ISO 문자열을 datetime-local 값으로. 없으면 빈 문자열 */
export const isoToLocal = (v: string | null | undefined) => (v ? toLocal(new Date(v).getTime()) : "");
export const fmt = (t: number) =>
  new Date(t).toLocaleString("ko-KR", { month: "long", day: "numeric", weekday: "short", hour: "2-digit", minute: "2-digit" });

/** 백엔드 검증 오류(details.fields)의 첫 메시지. 없으면 null */
export const firstFieldError = (details: Record<string, unknown> | null) => {
  const fields = details?.fields as Record<string, string> | undefined;
  return fields ? Object.values(fields)[0] ?? null : null;
};
