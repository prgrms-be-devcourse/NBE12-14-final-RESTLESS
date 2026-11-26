"use client";

import { useSyncExternalStore } from "react";
import "./intro.css";

/**
 * 인트로 연출 켜기, 끄기 설정. 자주 오는 사람이 긴 스크롤 연출을 매번 지나지 않도록
 * localStorage에 저장하고, 저장한 값이 없으면 움직임 줄이기 설정을 따른다.
 */
const KEY = "fanq.intro";
const EVENT = "fanq:intro";
const REDUCE = "(prefers-reduced-motion: reduce)";

// 첫 페인트 전 스크립트는 app/layout.tsx의 introBoot에 있다. 키와 기본값을 함께 맞춘다

function read(): boolean {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "on") return true;
    if (v === "off") return false;
  } catch {
    /* 저장소를 못 쓰면 기본값 */
  }
  return !window.matchMedia(REDUCE).matches;
}

function subscribe(cb: () => void) {
  const m = window.matchMedia(REDUCE);
  const onStorage = (e: StorageEvent) => e.key === KEY && cb();
  window.addEventListener(EVENT, cb);
  window.addEventListener("storage", onStorage);
  m.addEventListener("change", cb);
  return () => {
    window.removeEventListener(EVENT, cb);
    window.removeEventListener("storage", onStorage);
    m.removeEventListener("change", cb);
  };
}

/** 연출을 보여 줄지. 서버 렌더와 하이드레이션 중에는 아직 모르므로 null이다 */
export function useIntro(): boolean | null {
  return useSyncExternalStore(subscribe, read, () => null);
}

export function setIntro(on: boolean) {
  const v = on ? "on" : "off";
  try {
    localStorage.setItem(KEY, v);
  } catch {
    /* 저장하지 못해도 이번 방문에는 반영한다 */
  }
  document.documentElement.setAttribute("data-intro", v);
  window.dispatchEvent(new Event(EVENT));
}

/**
 * 연출을 감싸는 문. 꺼져 있으면 아예 그리지 않아 스크롤 연출 없이 본문부터 시작한다.
 * 설정을 아직 모르는 첫 렌더에서는 그려 두되, 첫 페인트 전 스크립트가 꺼짐을 표시했으면 CSS로 숨긴다.
 */
export function IntroGate({ children }: { children: React.ReactNode }) {
  const on = useIntro();
  if (on === false) return null;
  return (
    <div className="fq-intro" data-pending={on === null ? "" : undefined}>
      {children}
    </div>
  );
}
