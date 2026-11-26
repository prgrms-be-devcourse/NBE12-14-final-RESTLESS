"use client";

import { useEffect, useRef } from "react";

/**
 * 본 화면 배경. 깊이가 다른 작은 별이 아주 천천히 흐르고, 가까운 별끼리 가는 선으로 이어진다.
 * 마우스 근처의 별만 조금 밝아진다. 스크롤하면 깊이에 따라 다른 속도로 밀려 원근감이 생긴다.
 * 내용 뒤에서 조용히 있어야 하므로 초당 30장만 그리고, 탭이 가려지면 멈춘다.
 */
export default function Constellation({ tone = "dark" }: { tone?: "dark" | "light" }) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const ink = tone === "light" ? "40,34,90" : "226,222,255";
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    type Star = { x: number; y: number; z: number; vx: number; vy: number; ph: number };
    let stars: Star[] = [];
    let w = 0;
    let h = 0;
    let raf = 0;
    let last = 0;
    let mx = -9999;
    let my = -9999;

    const build = () => {
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // 화면 넓이에 맞춰 개수를 정한다. 너무 촘촘하면 선이 거미줄처럼 보인다
      const n = Math.round(Math.min(110, Math.max(36, (w * h) / 16000)));
      stars = Array.from({ length: n }, () => ({
        x: Math.random() * w,
        y: Math.random() * h,
        z: 0.25 + Math.random() * 0.75,
        vx: (Math.random() - 0.5) * 0.12,
        vy: (Math.random() - 0.5) * 0.12,
        ph: Math.random() * Math.PI * 2,
      }));
    };

    const LINK = 132;
    const draw = (now: number) => {
      ctx.clearRect(0, 0, w, h);
      const sy = window.scrollY;
      const px: number[] = [];
      const py: number[] = [];
      for (const s of stars) {
        if (!reduce) {
          s.x += s.vx * s.z;
          s.y += s.vy * s.z;
          if (s.x < -20) s.x = w + 20;
          if (s.x > w + 20) s.x = -20;
          if (s.y < -20) s.y = h + 20;
          if (s.y > h + 20) s.y = -20;
        }
        // 가까운 별일수록 스크롤에 더 많이 밀린다
        const y = (((s.y - sy * s.z * 0.06) % (h + 40)) + h + 40) % (h + 40) - 20;
        px.push(s.x);
        py.push(y);
      }
      ctx.lineWidth = 1;
      for (let i = 0; i < stars.length; i++) {
        for (let j = i + 1; j < stars.length; j++) {
          const dx = px[i] - px[j];
          const dy = py[i] - py[j];
          const d2 = dx * dx + dy * dy;
          if (d2 > LINK * LINK) continue;
          const a = (1 - Math.sqrt(d2) / LINK) * 0.11 * Math.min(stars[i].z, stars[j].z);
          ctx.strokeStyle = `rgba(${ink},${a})`;
          ctx.beginPath();
          ctx.moveTo(px[i], py[i]);
          ctx.lineTo(px[j], py[j]);
          ctx.stroke();
        }
      }
      for (let i = 0; i < stars.length; i++) {
        const s = stars[i];
        const near = Math.max(0, 1 - Math.hypot(px[i] - mx, py[i] - my) / 190);
        const tw = reduce ? 1 : 0.75 + Math.sin(now / 1400 + s.ph) * 0.25;
        const a = Math.min(1, (0.22 + s.z * 0.5) * tw + near * 0.5);
        const r = 0.5 + s.z * 0.9 + near * 0.8;
        ctx.fillStyle = `rgba(${ink},${a})`;
        ctx.beginPath();
        ctx.arc(px[i], py[i], r, 0, Math.PI * 2);
        ctx.fill();
        if (near > 0.05) {
          ctx.strokeStyle = `rgba(${ink},${near * 0.16})`;
          ctx.beginPath();
          ctx.moveTo(px[i], py[i]);
          ctx.lineTo(mx, my);
          ctx.stroke();
        }
      }
    };

    const frame = (now: number) => {
      raf = requestAnimationFrame(frame);
      if (now - last < 33) return;
      last = now;
      draw(now);
    };
    const start = () => {
      if (raf || document.hidden) return;
      if (reduce) draw(0);
      else raf = requestAnimationFrame(frame);
    };
    const stop = () => {
      cancelAnimationFrame(raf);
      raf = 0;
    };
    const onVis = () => (document.hidden ? stop() : start());
    const onMove = (e: PointerEvent) => {
      if (e.pointerType !== "mouse") return;
      mx = e.clientX;
      my = e.clientY;
    };
    const onResize = () => {
      build();
      if (reduce) draw(0);
    };
    build();
    start();
    window.addEventListener("resize", onResize);
    window.addEventListener("pointermove", onMove, { passive: true });
    document.addEventListener("visibilitychange", onVis);
    return () => {
      stop();
      window.removeEventListener("resize", onResize);
      window.removeEventListener("pointermove", onMove);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [tone]);

  return <canvas ref={ref} style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} aria-hidden="true" />;
}
