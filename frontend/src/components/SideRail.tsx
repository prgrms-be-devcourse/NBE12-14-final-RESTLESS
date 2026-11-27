// Lucian UX/UI Prompt #040, MIT License (c) 2026 Seokwoooo, https://lucianlabs.dev/uxui-prompt/#040
"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth";
import { useTheme } from "@/lib/hooks";
import Constellation from "./Constellation";
import IntroToggle from "./IntroToggle";
import ThemeToggle from "./ThemeToggle";
import "./SideRail.css";

export type RailItem = {
  href: string;
  label: string;
  eyebrow: string;
  icon: React.ReactNode;
  match?: string[];
  badge?: number;
  /** 넓은 화면에서는 메뉴에서 뺀다. 홈은 Q 로고가, 마이페이지는 아래 프로필 단추가 대신한다. 모바일 탭 바에는 남긴다 */
  wideHidden?: boolean;
};

const I = {
  home: <path d="M4 11l8-6.5 8 6.5V19a1 1 0 0 1-1 1h-4.5v-5h-5v5H5a1 1 0 0 1-1-1z" />,
  ticket: (
    <path d="M4 7.5A1.5 1.5 0 0 1 5.5 6h13A1.5 1.5 0 0 1 20 7.5V10a2 2 0 0 0 0 4v2.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 16.5V14a2 2 0 0 0 0-4zM14 6v12" strokeDasharray="0" />
  ),
  drop: (
    <>
      <path d="M5 8.5h14l-1.2 10.2a1.5 1.5 0 0 1-1.5 1.3H7.7a1.5 1.5 0 0 1-1.5-1.3z" />
      <path d="M9 8.5V7a3 3 0 0 1 6 0v1.5" />
    </>
  ),
  gavel: (
    <>
      <path d="M13.5 4.5l6 6M11 7l6 6M12.5 5.5l-4 4 6 6 4-4" />
      <path d="M9.5 12.5L4 18a1.4 1.4 0 0 0 2 2l5.5-5.5" />
    </>
  ),
  heart: <path d="M12 19.5s-7.5-4.4-7.5-9.7A4.3 4.3 0 0 1 12 7.4a4.3 4.3 0 0 1 7.5 2.4c0 5.3-7.5 9.7-7.5 9.7z" />,
  me: (
    <>
      <circle cx="12" cy="8.5" r="3.8" />
      <path d="M4.5 20a7.5 7.5 0 0 1 15 0" />
    </>
  ),
  admin: <path d="M5 19.5V13M5 9V4.5M12 19.5V11M12 7V4.5M19 19.5V15M19 11V4.5M3 13h4M10 7h4M17 15h4" />,
};

export const RAIL_ITEMS: RailItem[] = [
  { href: "/home", label: "홈", eyebrow: "LIVE SPACE", icon: I.home, wideHidden: true },
  { href: "/concerts", label: "공연, 티켓", eyebrow: "LIVE CONCERT", icon: I.ticket, match: ["/concerts", "/performances"] },
  { href: "/drops", label: "한정판 드롭", eyebrow: "LIMITED DROP", icon: I.drop },
  { href: "/fan-meetings", label: "팬미팅 추첨", eyebrow: "FAN MEETING", icon: I.heart },
  { href: "/auctions", label: "라이브 경매", eyebrow: "LIVE AUCTION", icon: I.gavel },
  { href: "/me", label: "마이페이지", eyebrow: "MY SPACE", icon: I.me, match: ["/me", "/checkout", "/login", "/signup"], wideHidden: true },
  { href: "/admin", label: "관리, 주최", eyebrow: "CONSOLE", icon: I.admin, match: ["/admin", "/organizer"] },
];

export function activeItem(pathname: string) {
  return (
    RAIL_ITEMS.find((it) => (it.match ?? [it.href]).some((m) => pathname === m || pathname.startsWith(m + "/"))) ??
    RAIL_ITEMS[0]
  );
}

export default function SideRail({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { profile, ready, logout } = useAuth();
  const [wide, setWide] = useState(false);
  const tone = useTheme();
  const liRefs = useRef<(HTMLLIElement | null)[]>([]);
  const linkRefs = useRef<(HTMLAnchorElement | null)[]>([]);
  const [hlY, setHlY] = useState(0);
  const allowedAdmin = profile?.role === "ADMIN" || profile?.role === "ORGANIZER";
  const visibleItems = RAIL_ITEMS.filter((item) => item.href !== "/admin" || (ready && allowedAdmin));
  const current = activeItem(pathname);
  const idx = visibleItems.indexOf(current);

  // 펼친 메뉴는 본문 위에 뜨는 판이라, 다른 화면으로 가면 닫는다
  useEffect(() => {
    setWide(false);
  }, [pathname]);
  // 바깥을 누르거나 Esc를 누르면 닫는다
  const railRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!wide) return;
    const onDown = (e: PointerEvent) => {
      if (!railRef.current?.contains(e.target as Node)) setWide(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setWide(false);
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [wide]);

  // 강조 칸을 선택된 메뉴 위치로 미끄러뜨린다
  useLayoutEffect(() => {
    const li = liRefs.current[idx];
    if (li) setHlY(li.offsetTop);
  }, [idx]);

  // 제목이 살짝 내려앉으며 바뀐다
  const [title, setTitle] = useState(current);
  const [swap, setSwap] = useState(false);
  useEffect(() => {
    if (title.href === current.href) return;
    setSwap(true);
    const t = setTimeout(() => {
      setTitle(current);
      setSwap(false);
    }, 180);
    return () => clearTimeout(t);
  }, [current, title.href]);

  const toggle = () => setWide((w) => !w);

  return (
    <div className={`u040${wide ? " is-wide" : ""}`}>
      <div className="u040-sky" aria-hidden="true">
        {/* 본 화면 배경: 빛줄기가 날아다니는 별빛 대신 조용한 별자리 */}
        <Constellation tone={tone} />
      </div>
      <nav ref={railRef} className="u040-rail" aria-label="주 메뉴">
        <Link href="/home" className="u040-top" aria-label="FANQ 홈으로" aria-current={pathname === "/home" ? "page" : undefined}>
          <span className="u040-logo" aria-hidden="true">
            Q
          </span>
          <span className="u040-name">FANQ</span>
        </Link>
        <button
          className="u040-tog"
          type="button"
          aria-expanded={wide}
          aria-label={wide ? "메뉴 접기" : "메뉴 펼치기"}
          onClick={toggle}
        >
          <svg viewBox="0 0 16 16">
            <path d="M6 3.5L10.5 8 6 12.5" />
          </svg>
        </button>
        <ul className="u040-nav">
          <li className={`u040-hl${current.wideHidden ? " is-wide-hidden" : ""}`} role="presentation" style={{ "--y": `${hlY}px` } as React.CSSProperties} />
          {visibleItems.map((it, i) => (
            <li
              key={it.href}
              className={it.wideHidden ? "is-wide-hidden" : undefined}
              ref={(el) => {
                liRefs.current[i] = el;
              }}
            >
              <Link
                href={it.href}
                ref={(el) => {
                  linkRefs.current[i] = el;
                }}
                className="u040-it"
                style={{ "--i": i } as React.CSSProperties}
                aria-current={i === idx ? "page" : undefined}
                title={wide ? undefined : it.label}
                onKeyDown={(e) => {
                  const d = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : 0;
                  if (d) {
                    e.preventDefault();
                    linkRefs.current[(i + d + visibleItems.length) % visibleItems.length]?.focus();
                  }
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  {it.icon}
                </svg>
                <span>{it.label}</span>
                {it.badge ? (
                  <em className="u040-bd" aria-label={`진행 중 ${it.badge}개`}>
                    {it.badge}
                  </em>
                ) : null}
              </Link>
            </li>
          ))}
        </ul>
        <div className="u040-foot">
          <ThemeToggle className="u040-theme" />
          <IntroToggle className="u040-theme" />
          {profile ? (
            <div className="u040-me">
              <Link href="/me" className="u040-me-link" aria-label={`${profile.nickname}, 마이페이지`}>
                <i aria-hidden="true" />
                <span>
                  {profile.nickname}
                  <small>{profile.role === "ADMIN" ? "관리자" : profile.role === "ORGANIZER" ? "주최자" : "팬"}</small>
                </span>
              </Link>
              <button
                type="button"
                className="u040-out"
                aria-label="로그아웃"
                onClick={async () => {
                  await logout();
                  router.push("/home");
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M14 4.5h3.5a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H14M10 8l-4 4 4 4M6 12h9" />
                </svg>
              </button>
            </div>
          ) : (
            <Link href="/login" className="u040-me is-guest" aria-label="로그인">
              <i aria-hidden="true" />
              <span>
                {ready ? "로그인" : "…"}
                <small>또는 회원가입</small>
              </span>
            </Link>
          )}
        </div>
      </nav>

      <section className="u040-main">
        <header className="u040-head">
          <div>
            <small>FANQ {title.eyebrow}</small>
            <h1 className={swap ? "swap" : undefined}>{title.label}</h1>
          </div>
          <div className="u040-head-r">
            <ThemeToggle className="u040-head-theme" />
            <IntroToggle className="u040-head-theme" />
            {/* 레일의 로그아웃은 메뉴를 펼쳐야 보이므로, 메인 화면에서는 머리말에도 둔다 */}
            {pathname === "/home" && profile ? (
              <button type="button" className="u040-head-out" onClick={() => logout()}>
                로그아웃
              </button>
            ) : null}
          </div>
        </header>
        <div className="u040-body">{children}</div>
      </section>
    </div>
  );
}
