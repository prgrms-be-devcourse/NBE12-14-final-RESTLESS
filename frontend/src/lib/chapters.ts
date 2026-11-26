/** 스크롤 연출을 장(챕터)으로 나누는 도구 */

export const clamp01 = (t: number) => Math.min(1, Math.max(0, t));
/** a에서 b 사이를 0~1로 바꾼다. 범위 밖은 0 또는 1 */
export const ramp = (t: number, a: number, b: number) => clamp01((t - a) / (b - a));
export const smooth = (t: number) => t * t * (3 - 2 * t);
export const mix = (a: number, b: number, t: number) => a + (b - a) * t;

/**
 * 전체 진행률 p(0~1)를 장 길이 비율(spans)로 나눠 지금 장 번호와 그 장 안의 진행률(local)을 준다.
 * 예: spans [1, 2, 1]이면 두 번째 장이 화면 스크롤의 절반을 쓴다.
 */
export function chapterAt(p: number, spans: number[]) {
  const total = spans.reduce((a, b) => a + b, 0);
  let acc = 0;
  for (let i = 0; i < spans.length; i++) {
    const start = acc / total;
    acc += spans[i];
    const end = acc / total;
    if (p < end || i === spans.length - 1) return { index: i, local: clamp01((p - start) / (end - start)), start, end };
  }
  return { index: spans.length - 1, local: 1, start: 0, end: 1 };
}

/** 장 i가 시작하는 전체 진행률. 목록에서 장으로 바로 이동할 때 쓴다 */
export function chapterStart(i: number, spans: number[]) {
  const total = spans.reduce((a, b) => a + b, 0);
  return spans.slice(0, i).reduce((a, b) => a + b, 0) / total;
}
