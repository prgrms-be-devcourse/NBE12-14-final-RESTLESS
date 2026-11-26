"use client";

/** 성공 순간(응모 완료, 입찰 성공, 좌석 확보)에 터뜨리는 꽃가루. 의존성 없이 캔버스 한 장으로 그린다. */
const COLORS = ["#B7A4FF", "#7DB4FF", "#A5F2D3", "#FF7A93", "#FFD36E", "#6A57F2"];

export function burst(origin?: { x: number; y: number }, count = 140) {
  if (typeof window === "undefined") return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const cv = document.createElement("canvas");
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const W = window.innerWidth;
  const H = window.innerHeight;
  cv.width = W * dpr;
  cv.height = H * dpr;
  Object.assign(cv.style, { position: "fixed", inset: "0", width: "100%", height: "100%", pointerEvents: "none", zIndex: "9999" });
  document.body.appendChild(cv);
  const cx = cv.getContext("2d")!;
  cx.scale(dpr, dpr);
  const ox = origin?.x ?? W / 2;
  const oy = origin?.y ?? H / 2.4;
  const ps = Array.from({ length: count }, () => {
    const a = Math.random() * Math.PI * 2;
    const v = 6 + Math.random() * 10;
    return {
      x: ox,
      y: oy,
      vx: Math.cos(a) * v,
      vy: Math.sin(a) * v - 6,
      r: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.4,
      w: 6 + Math.random() * 6,
      h: 3 + Math.random() * 4,
      c: COLORS[(Math.random() * COLORS.length) | 0],
    };
  });
  const start = performance.now();
  function frame(now: number) {
    const t = now - start;
    cx.clearRect(0, 0, W, H);
    ps.forEach((p) => {
      p.vy += 0.32;
      p.vx *= 0.985;
      p.x += p.vx;
      p.y += p.vy;
      p.r += p.vr;
      cx.save();
      cx.translate(p.x, p.y);
      cx.rotate(p.r);
      cx.globalAlpha = Math.max(0, 1 - t / 1800);
      cx.fillStyle = p.c;
      cx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
      cx.restore();
    });
    if (t < 1800) requestAnimationFrame(frame);
    else cv.remove();
  }
  requestAnimationFrame(frame);
}

export function burstFrom(el: Element | null) {
  if (!el) return burst();
  const r = el.getBoundingClientRect();
  burst({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
}
