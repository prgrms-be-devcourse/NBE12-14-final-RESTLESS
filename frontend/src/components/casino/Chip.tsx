import "./casino.css";

const COLORS = [
  ["#d4243a", "#7a0f1d"],
  ["#1f6f5c", "#0b3a2f"],
  ["#2a3c8f", "#121c4d"],
  ["#d99a1e", "#7a5208"],
  ["#1b1b22", "#050507"],
];
const EDGE = 12;

/**
 * 돌아가는 포커 칩. 얇은 원판을 겹쳐 두께를 만들고, 앞뒤 면에 줄무늬 테두리와 번호를 그린다.
 * 장식이라 스크린 리더에는 숨긴다.
 */
export default function Chip({ seed, top, big, size = 220, still = false }: { seed: number; top: string; big: string; size?: number; still?: boolean }) {
  const [c1, c2] = COLORS[Math.abs(seed) % COLORS.length];
  const face = (back: boolean) => (
    <div className={`fq-chip3d-face${back ? " is-back" : ""}`}>
      <div className="fq-chip3d-core">
        <small>{top}</small>
        <b>{big}</b>
      </div>
    </div>
  );
  return (
    <div className={`fq-chip3d${still ? " is-still" : ""}`} style={{ "--c1": c1, "--c2": c2, "--size": `${size}px` } as React.CSSProperties} aria-hidden="true">
      <div className="fq-chip3d-spin">
        {Array.from({ length: EDGE }, (_, i) => (
          <i key={i} className="fq-chip3d-edge" style={{ "--k": i - EDGE / 2 } as React.CSSProperties} />
        ))}
        {face(false)}
        {face(true)}
      </div>
      <span className="fq-chip3d-shadow" />
    </div>
  );
}
