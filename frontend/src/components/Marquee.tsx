// Lucian UX/UI Prompt #394, MIT License (c) 2026 Seokwoooo, https://lucianlabs.dev/uxui-prompt/#394
"use client";

import { useEffect, useRef } from "react";
import "./Marquee.css";

/**
 * 세 줄 문구 띠. 줄마다 방향을 엇갈려 일정한 속도로 흐른다.
 * 원본(#394)의 스크롤 가속, 기울기는 홈 화면이 정신없어 보여 뺐다.
 */
export default function Marquee({ rows, label }: { rows: [string, string, string]; label: string }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef<(HTMLDivElement | null)[]>([]);

  useEffect(() => {
    const root = rootRef.current!;
    const rm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const state = rows.map((_, i) => ({ x: -Math.random() * 200, dir: i % 2 ? 1 : -1, w: 0 }));
    let last = 0;
    let vis = true;
    let raf = 0;

    const frame = (now: number) => {
      raf = 0;
      const dt = Math.max(0, Math.min(50, now - (last || now))) / 16.7;
      last = now;
      state.forEach((r, i) => {
        const el = rowRefs.current[i];
        if (!el) return;
        if (!r.w) r.w = (el.firstChild as HTMLElement).offsetWidth;
        r.x += r.dir * 0.5 * dt;
        if (r.x < -r.w) r.x += r.w;
        if (r.x > 0) r.x -= r.w;
        el.style.transform = `translateX(${r.x.toFixed(1)}px)`;
      });
      if (vis && !rm) raf = requestAnimationFrame(frame);
    };
    const io = new IntersectionObserver((en) => {
      const was = vis;
      vis = en[0].isIntersecting;
      if (vis && !was && !raf) raf = requestAnimationFrame(frame);
    });
    io.observe(root);
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows.join("|")]);

  return (
    <div ref={rootRef} className="u394" role="marquee" aria-label={label}>
      <div className="u394-st" aria-hidden="true">
        {rows.map((t, i) => (
          <div
            key={i}
            className="u394-row"
            ref={(el) => {
              rowRefs.current[i] = el;
            }}
          >
            {[0, 1, 2, 3].map((k) => (
              <span key={k}>{t}</span>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
