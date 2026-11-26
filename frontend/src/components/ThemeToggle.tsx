"use client";

import { useTheme } from "@/lib/hooks";

const KEY = "fanq.theme";

/** 우주(다크, 기본) ↔ 새벽(라이트). data-theme 속성으로 토큰을 바꾼다. */
export default function ThemeToggle({ className }: { className?: string }) {
  const mode = useTheme();

  const next = () => {
    const m = mode === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", m);
    try {
      localStorage.setItem(KEY, m);
    } catch {
      /* 무시 */
    }
  };

  const label = mode === "dark" ? "다크 모드" : "라이트 모드";
  return (
    <button type="button" className={className} onClick={next} aria-label={`테마 바꾸기, 지금 ${label}`}>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {mode === "dark" ? (
          <path d="M19.5 14.5A7.5 7.5 0 0 1 9.5 4.5a7.5 7.5 0 1 0 10 10z" />
        ) : (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4" />
          </>
        )}
      </svg>
      <span>{label}</span>
    </button>
  );
}
