"use client";

import Lenis from "lenis";
import { usePathname } from "next/navigation";
import { useEffect } from "react";

let lenis: Lenis | null = null;

/** 관성 스크롤이 켜져 있으면 그걸로, 아니면 브라우저 기본 부드러운 스크롤로 이동한다 */
export function scrollToY(y: number, opts?: { immediate?: boolean }) {
  if (lenis) lenis.scrollTo(y, { immediate: opts?.immediate, duration: opts?.immediate ? 0 : 1.4 });
  else window.scrollTo({ top: y, behavior: opts?.immediate ? "auto" : "smooth" });
}
export function scrollToEl(el: Element | null, offset = -16) {
  if (!el) return;
  scrollToY(el.getBoundingClientRect().top + window.scrollY + offset);
}

/**
 * 페이지 전체에 관성 스크롤을 건다. 화면이 실제 window 스크롤을 움직이므로
 * 기존 scroll 이벤트와 sticky 레이아웃은 그대로 동작한다.
 * 움직임 줄이기 설정이 켜져 있으면 쓰지 않는다. 같은 페이지 안의 #링크도 관성 스크롤로 옮긴다.
 */
export default function SmoothScroll() {
  const pathname = usePathname();

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const l = new Lenis({ lerp: 0.085, smoothWheel: true, wheelMultiplier: 0.9, touchMultiplier: 1.2 });
    lenis = l;
    let raf = 0;
    const loop = (t: number) => {
      l.raf(t);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);

    const onClick = (e: MouseEvent) => {
      const a = (e.target as HTMLElement).closest?.("a[href^='#']") as HTMLAnchorElement | null;
      if (!a) return;
      const target = document.querySelector(a.getAttribute("href")!);
      if (!target) return;
      e.preventDefault();
      scrollToEl(target);
    };
    document.addEventListener("click", onClick);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("click", onClick);
      l.destroy();
      lenis = null;
    };
  }, []);

  // 다른 화면으로 옮기면 맨 위에서 시작한다
  useEffect(() => {
    lenis?.scrollTo(0, { immediate: true, force: true });
  }, [pathname]);

  return null;
}
