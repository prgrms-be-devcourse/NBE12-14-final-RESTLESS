"use client";

import { useEffect } from "react";
import { setIntro, useIntro } from "@/lib/intro";

/** 인트로 연출 켜기 ↔ 끄기. 다음 방문에도 유지된다 */
export default function IntroToggle({ className }: { className?: string }) {
  const value = useIntro();
  const on = value !== false;
  // 움직임 줄이기 설정이 바뀌는 등 저장값 없이 바뀐 경우에도 html[data-intro]를 맞춰 둔다
  useEffect(() => {
    if (value !== null) document.documentElement.setAttribute("data-intro", value ? "on" : "off");
  }, [value]);
  const label = on ? "연출 켜짐" : "연출 꺼짐";
  return (
    <button type="button" className={className} onClick={() => setIntro(!on)} aria-pressed={!on} aria-label={`인트로 연출 ${on ? "끄기" : "켜기"}, 지금 ${label}`}>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {on ? (
          <>
            <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
            <path d="M10 9.2v5.6l4.6-2.8z" />
          </>
        ) : (
          <>
            <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
            <path d="M8 9h8M8 12h8M8 15h5" />
          </>
        )}
      </svg>
      <span>{label}</span>
    </button>
  );
}
