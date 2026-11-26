"use client";

import { useEffect, type RefObject } from "react";

/**
 * 요소가 화면을 지나가는 진행률(0~1)을 매 프레임 CSS 변수 --p로 넣고, 필요하면 콜백으로도 알려 준다.
 * 진행률은 요소 위쪽이 화면 위에 닿을 때 0, 요소 아래쪽이 화면 아래에 닿을 때 1이다.
 * mode가 "enter"면 요소가 화면 아래로 들어오기 시작할 때 0, 화면 위에 닿을 때 1이다.
 */
export function useScrollProgress(ref: RefObject<HTMLElement | null>, onProgress?: (p: number) => void, mode: "pin" | "enter" = "pin") {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let raf = 0;
    let last = -1;
    const read = () => {
      raf = 0;
      const r = el.getBoundingClientRect();
      const vh = window.innerHeight;
      const p =
        mode === "pin"
          ? Math.min(1, Math.max(0, -r.top / Math.max(1, r.height - vh)))
          : Math.min(1, Math.max(0, (vh - r.top) / Math.max(1, vh)));
      if (Math.abs(p - last) < 0.0005) return;
      last = p;
      el.style.setProperty("--p", p.toFixed(4));
      onProgress?.(p);
    };
    const kick = () => {
      if (!raf) raf = requestAnimationFrame(read);
    };
    read();
    window.addEventListener("scroll", kick, { passive: true });
    window.addEventListener("resize", kick);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("scroll", kick);
      window.removeEventListener("resize", kick);
    };
    // 콜백은 매 렌더 바뀌어도 구독을 다시 만들 필요가 없다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, mode]);
}
