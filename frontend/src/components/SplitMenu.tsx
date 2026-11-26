// Lucian UX/UI Prompt #312, MIT License (c) 2026 Seokwoooo, https://lucianlabs.dev/uxui-prompt/#312
"use client";

import { useRouter } from "next/navigation";
import { useRef } from "react";
import "./SplitMenu.css";

export type SplitMenuItem = { word: string; desc: string; href: string; tag?: string };

/**
 * 단어를 위, 아래 두 장으로 겹쳐 두고, 포인터가 가까울수록 벌어지며 사이에 설명 줄이 보인다.
 * 원본의 구조와 모션(.5s cubic-bezier(.3,1.3,.4,1))을 유지하고, 글자 크기만 화면 폭에 맞춰 키웠다.
 */
export default function SplitMenu({
  items,
  label = "주요 메뉴",
  onPick,
}: {
  items: SplitMenuItem[];
  label?: string;
  /** 항목을 고른 순간(이동 직전) 알려준다 */
  onPick?: (item: SplitMenuItem) => void;
}) {
  const router = useRouter();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const open = (el: HTMLElement | null, o: number) => el?.style.setProperty("--o", o.toFixed(1));

  return (
    <nav className="u312" aria-label={label}>
      {items.map((it, i) => (
        <button
          key={it.word}
          ref={(el) => {
            refs.current[i] = el;
          }}
          className="u312-it"
          type="button"
          aria-label={`${it.word}, ${it.desc}`}
          onPointerMove={(e) => {
            const r = e.currentTarget.getBoundingClientRect();
            const y = (e.clientY - r.top) / (r.height || 1);
            open(e.currentTarget, 10 + 12 * (1 - Math.min(1, Math.abs(y - 0.5) * 2)));
          }}
          onPointerLeave={(e) => {
            if (document.activeElement !== e.currentTarget) open(e.currentTarget, 0);
          }}
          onFocus={(e) => open(e.currentTarget, 18)}
          onBlur={(e) => open(e.currentTarget, 0)}
          onKeyDown={(e) => {
            const d = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : 0;
            if (d) {
              e.preventDefault();
              refs.current[(i + d + items.length) % items.length]?.focus();
            }
          }}
          onClick={(e) => {
            refs.current.forEach((o) => o !== e.currentTarget && open(o, 0));
            open(e.currentTarget, 22);
            const href = it.href;
            onPick?.(it);
            setTimeout(() => router.push(href), onPick ? 700 : 380);
          }}
        >
          <span className="u312-d" aria-hidden="true">
            {it.desc}
          </span>
          <span className="u312-h t" aria-hidden="true">
            {it.word}
          </span>
          <span className="u312-h b" aria-hidden="true">
            {it.word}
          </span>
          <span className="u312-n" aria-hidden="true">
            {String(i + 1).padStart(2, "0")}
            {it.tag ? <em>{it.tag}</em> : null}
          </span>
        </button>
      ))}
    </nav>
  );
}
