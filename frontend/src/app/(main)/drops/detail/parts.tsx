import { countdown, dateTime } from "@/lib/format";

/**
 * 대기열 계기판. 240도 호 위에 눈금을 두고, 처음 순번에서 지금까지 줄어든 만큼 호가 차오른다.
 * 가운데 숫자가 주인공이고 호는 얼마나 왔는지만 알려 준다.
 */
export function QueueGauge({ pos, pct }: { pos: number; pct: number }) {
  const R = 88;
  const SWEEP = 240;
  const START = 150; // 왼쪽 아래에서 시작해 시계 방향으로 돈다
  const len = (Math.PI * 2 * R * SWEEP) / 360;
  const p = Math.min(1, Math.max(0, pct / 100));
  const at = (deg: number, r: number) => {
    const a = (deg * Math.PI) / 180;
    return [100 + Math.cos(a) * r, 100 + Math.sin(a) * r];
  };
  const arc = (() => {
    const [x0, y0] = at(START, R);
    const [x1, y1] = at(START + SWEEP, R);
    return `M${x0} ${y0} A${R} ${R} 0 1 1 ${x1} ${y1}`;
  })();
  const [hx, hy] = at(START + SWEEP * p, R);
  const TICKS = 48;
  return (
    <div className="fq-gauge">
      <svg viewBox="0 0 200 180" aria-hidden="true">
        {Array.from({ length: TICKS + 1 }, (_, i) => {
          const deg = START + (SWEEP * i) / TICKS;
          const major = i % 6 === 0;
          const [x0, y0] = at(deg, R - (major ? 15 : 11));
          const [x1, y1] = at(deg, R - 7);
          return <line key={i} x1={x0} y1={y0} x2={x1} y2={y1} className={`${major ? "is-major" : ""}${i / TICKS <= p ? " is-on" : ""}`} />;
        })}
        <path d={arc} className="fq-gauge-track" />
        <path d={arc} className="fq-gauge-fill" style={{ strokeDasharray: len, strokeDashoffset: len * (1 - p) }} />
        <circle cx={hx} cy={hy} r="4.5" className="fq-gauge-head" style={{ transition: "cx 0.4s, cy 0.4s" }} />
      </svg>
      <div className="fq-gauge-c">
        <span>내 앞에 남은 팬</span>
        <b key={pos} className="fq-num">
          {pos.toLocaleString()}
        </b>
      </div>
    </div>
  );
}

/** 판매 기간 선. 오픈과 마감 사이에서 지금이 어디인지 점 하나로 보여 준다 */
export function DropTimeline({ opensAt, closesAt, now }: { opensAt: string; closesAt: string; now: number }) {
  const open = new Date(opensAt).getTime();
  const close = new Date(closesAt).getTime();
  const p = Math.min(1, Math.max(0, (now - open) / Math.max(1, close - open)));
  const before = now < open;
  const after = now >= close;
  return (
    <div className={`fq-tl${before ? " is-before" : after ? " is-after" : " is-live"}`}>
      <div className="fq-tl-line" style={{ "--p": p } as React.CSSProperties}>
        <i />
        <b />
      </div>
      <dl>
        <div>
          <dt>오픈</dt>
          <dd>{dateTime(opensAt)}</dd>
        </div>
        <div className="fq-tl-now">
          <dt>{before ? "오픈까지" : after ? "판매" : "마감까지"}</dt>
          <dd className="fq-num">{before ? countdown(opensAt, now) : after ? "종료" : countdown(closesAt, now)}</dd>
        </div>
        <div>
          <dt>마감</dt>
          <dd>{dateTime(closesAt)}</dd>
        </div>
      </dl>
    </div>
  );
}

const SPECS = [
  { k: "무게", v: "165", u: "g", d: "한 손으로 세 시간" },
  { k: "연속 사용", v: "18", u: "시간", d: "AAA 배터리 3개" },
  { k: "광원", v: "6", u: "LED", d: "RGB, 곡마다 바뀌는 무대 색" },
  { k: "구성", v: "8", u: "부품", d: "글라스 돔부터 엔드캡까지" },
  { k: "색상", v: "3", u: "종", d: "블랙, 화이트, 실버 한정" },
  { k: "연동", v: "BT", u: "", d: "블루투스로 공연장 연출과 연결" },
];

/** 응원봉 사양표. 큰 숫자와 가는 선만으로 정리한다. 아래 분해 쇼케이스가 보여 주는 내용과 같은 값이다 */
export function SpecSheet() {
  return (
    <section className="fq-spec" aria-label="응원봉 사양">
      <header>
        <span>SPECIFICATIONS</span>
        <h2>사양</h2>
      </header>
      <dl>
        {SPECS.map((s) => (
          <div key={s.k}>
            <dt>{s.k}</dt>
            <dd>
              <b className="fq-num">{s.v}</b>
              <i>{s.u}</i>
            </dd>
            <p>{s.d}</p>
          </div>
        ))}
      </dl>
    </section>
  );
}
