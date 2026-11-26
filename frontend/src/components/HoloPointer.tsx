"use client";

import { useEffect } from "react";

const TILT = ".fq-ccard, .fq-dl-art, .fq-dl-archive-card";

/**
 * 포스터의 홀로그램 박과 카드 기울기에 쓸 마우스 위치를 넣어 준다.
 * 포스터마다 리스너를 달지 않고 문서에 하나만 달아, 마우스 아래의 포스터와 카드에만 CSS 변수를 쓴다.
 *   포스터: --mx, --my (0~1, 왼쪽 위가 0)   카드: --rx, --ry (-1~1, 가운데가 0)
 * 터치 화면과 움직임 줄이기 설정에서는 아무것도 하지 않는다.
 */
export default function HoloPointer() {
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let raf = 0;
    let ev: PointerEvent | null = null;
    let tilted: HTMLElement | null = null;
    const apply = () => {
      raf = 0;
      const e = ev;
      if (!e) return;
      const t = e.target as HTMLElement | null;
      const poster = t?.closest<HTMLElement>(".fq-poster");
      if (poster) {
        const r = poster.getBoundingClientRect();
        poster.style.setProperty("--mx", ((e.clientX - r.left) / r.width).toFixed(3));
        poster.style.setProperty("--my", ((e.clientY - r.top) / r.height).toFixed(3));
      }
      const card = t?.closest<HTMLElement>(TILT) ?? null;
      if (tilted && tilted !== card) {
        tilted.style.removeProperty("--rx");
        tilted.style.removeProperty("--ry");
      }
      tilted = card;
      if (card) {
        const r = card.getBoundingClientRect();
        card.style.setProperty("--rx", (((e.clientX - r.left) / r.width) * 2 - 1).toFixed(3));
        card.style.setProperty("--ry", (((e.clientY - r.top) / r.height) * 2 - 1).toFixed(3));
      }
    };
    const onMove = (e: PointerEvent) => {
      if (e.pointerType !== "mouse") return;
      ev = e;
      if (!raf) raf = requestAnimationFrame(apply);
    };
    document.addEventListener("pointermove", onMove, { passive: true });
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("pointermove", onMove);
    };
  }, []);
  return null;
}
