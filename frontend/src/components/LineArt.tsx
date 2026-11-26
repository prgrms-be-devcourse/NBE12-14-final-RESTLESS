import "./LineArt.css";

export type LineArtKind = "ticket" | "box" | "card" | "chip" | "search";

/** 그림마다의 선. 점선은 dash로 표시해 그려지는 움직임에서 뺀다 */
const ART: Record<LineArtKind, { d: string; dash?: boolean }[]> = {
  // 비스듬히 놓인 티켓 한 장
  ticket: [
    { d: "M14 54 L70 26 L106 44 L50 72 Z" },
    { d: "M14 54 V60 L50 78 L106 50 V44" },
    { d: "M58 32 L94 50", dash: true },
    { d: "M30 53 L52 42 M36 58 L50 51" },
  ],
  // 뚜껑이 열린 상자
  box: [
    { d: "M20 48 L60 30 L100 48 L60 66 Z" },
    { d: "M20 48 V74 L60 92 L100 74 V48 M60 66 V92" },
    { d: "M20 48 L8 34 L48 16 L60 30 M100 48 L112 34 L72 16 L60 30" },
    { d: "M40 39 L80 57 M80 39 L40 57", dash: true },
  ],
  // 하트가 그려진 포토카드
  card: [
    { d: "M38 12 L88 20 L82 86 L32 78 Z" },
    { d: "M60 60 C46 51 47 38 56 39 C59 39.5 60 43 60 44 C60 43 62 39 66 39.5 C75 41 73 53 60 60 Z" },
    { d: "M42 70 L74 75", dash: true },
  ],
  // 눕혀 놓은 칩
  chip: [
    { d: "M24 44 A36 18 0 1 0 96 44 A36 18 0 1 0 24 44" },
    { d: "M24 44 V56 A36 18 0 0 0 96 56 V44" },
    { d: "M38 44 A22 11 0 1 0 82 44 A22 11 0 1 0 38 44" },
    { d: "M36 68 V58 M60 74 V62 M84 68 V58", dash: true },
  ],
  // 찾지 못했을 때: 빈 바닥 위의 돋보기
  search: [
    { d: "M30 42 A22 22 0 1 0 74 42 A22 22 0 1 0 30 42" },
    { d: "M68 58 L92 82" },
    { d: "M14 80 L60 96 L106 80", dash: true },
    { d: "M44 42 H60", dash: true },
  ],
};

/**
 * 빈 화면과 안내 화면에 쓰는 가는 선 그림. 색은 글자색을 따른다.
 * 화면에 나타날 때 선이 한 번 그려지고, 그 뒤로는 움직이지 않는다.
 */
export default function LineArt({ kind, size = 96 }: { kind: LineArtKind; size?: number }) {
  return (
    <svg className="fq-lineart" viewBox="0 0 120 100" width={size * 1.2} height={size} aria-hidden="true">
      {ART[kind].map((p, i) => (
        <path key={i} d={p.d} pathLength={p.dash ? undefined : 1} className={p.dash ? "is-dash" : undefined} style={{ animationDelay: `${i * 160}ms` }} />
      ))}
    </svg>
  );
}
