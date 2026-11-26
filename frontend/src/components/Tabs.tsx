"use client";

import { useLayoutEffect, useRef, useState } from "react";

/** 강조 알약이 선택한 탭으로 미끄러지는 필터 탭 (사이드 레일 강조 칸과 같은 spring 곡선) */
export default function Tabs<T extends string>({
  value,
  onChange,
  items,
  label,
}: {
  value: T;
  onChange: (v: T) => void;
  items: { value: T; label: string; count?: number }[];
  label: string;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const [pill, setPill] = useState({ x: 0, w: 0 });
  const idx = Math.max(0, items.findIndex((i) => i.value === value));

  const counts = items.map((i) => i.count ?? "").join(",");
  useLayoutEffect(() => {
    const measure = () => {
      const el = refs.current[idx];
      if (el) setPill({ x: el.offsetLeft, w: el.offsetWidth });
    };
    measure();
    // 웹폰트가 늦게 들어오거나 화면 폭이 바뀌면 탭 너비가 달라지므로 다시 잰다
    document.fonts?.ready.then(measure);
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [idx, items.length, counts]);

  return (
    <div className="fq-tabs" role="tablist" aria-label={label}>
      <span className="fq-tabs-pill" style={{ transform: `translateX(${pill.x}px)`, width: pill.w }} aria-hidden="true" />
      {items.map((it, i) => (
        <button
          key={it.value}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="button"
          role="tab"
          aria-selected={i === idx}
          tabIndex={i === idx ? 0 : -1}
          onClick={() => onChange(it.value)}
          onKeyDown={(e) => {
            const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
            if (d) {
              const n = (i + d + items.length) % items.length;
              refs.current[n]?.focus();
              onChange(items[n].value);
            }
          }}
        >
          {it.label}
          {it.count !== undefined ? <small>{it.count}</small> : null}
        </button>
      ))}
    </div>
  );
}
