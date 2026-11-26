"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import ChapterHud from "@/components/chapter/ChapterHud";
import { chapterAt, ramp, smooth } from "@/lib/chapters";
import type { FinaleState } from "./BoxFinale";
import "./domain-box.css";

const BoxFinale = dynamic(() => import("./BoxFinale"), { ssr: false });

export type Domain = "concert" | "drop" | "fanmeeting" | "auction";
const DOMAINS: { key: Domain; title: string; object: string; href: string; world: string }[] = [
  { key: "concert", title: "공연 예매", object: "티켓", href: "/concerts", world: "#3d8bff" },
  { key: "drop", title: "한정판 드롭", object: "응원봉", href: "/drops", world: "#ff7a45" },
  { key: "fanmeeting", title: "팬미팅 추첨", object: "포토카드", href: "/fan-meetings", world: "#ff7a93" },
  { key: "auction", title: "라이브 경매", object: "칩", href: "/auctions", world: "#e3263a" },
];
const SPANS = [1.5, 1.3];

/**
 * 도메인 목록 페이지의 마지막 화면. 다른 세 물건은 이미 상자에 들어 있고,
 * 이 페이지의 물건 하나가 제 칸에 떨어진 뒤 뚜껑이 닫힌다. 홈 마무리와 같은 상자를 쓴다.
 * 3D 장면은 이 구간이 화면 가까이 왔을 때만 불러온다.
 */
export default function DomainBox({ domain }: { domain: Domain }) {
  const rootRef = useRef<HTMLElement>(null);
  const state = useRef<FinaleState>({ index: -1, local: 0 });
  const [near, setNear] = useState(false);
  const [chapter, setChapter] = useState(0);
  const solo = DOMAINS.findIndex((d) => d.key === domain);
  const me = DOMAINS[solo];

  useEffect(() => {
    const root = rootRef.current!;
    let raf = 0;
    let target = 0;
    let shown = 0;
    let cur = 0;
    let on = false;
    const read = () => {
      const r = root.getBoundingClientRect();
      const span = root.offsetHeight - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -r.top / span)) : 0;
      on = r.top < window.innerHeight && r.bottom > 0;
    };
    const frame = () => {
      raf = 0;
      shown += (target - shown) * 0.12;
      if (Math.abs(target - shown) < 0.0003) shown = target;
      const ch = chapterAt(shown, SPANS);
      state.current = on ? { index: ch.index, local: ch.local } : { index: -1, local: 0 };
      root.style.setProperty("--p", shown.toFixed(4));
      root.style.setProperty("--copy", (ch.index === 0 ? 1 : 1 - smooth(ramp(ch.local, 0, 0.4))).toFixed(3));
      root.style.setProperty("--end", (ch.index === 1 ? smooth(ramp(ch.local, 0.62, 0.95)) : 0).toFixed(3));
      if (ch.index !== cur) {
        cur = ch.index;
        setChapter(ch.index);
      }
      if (shown !== target) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      read();
      if (!raf) raf = requestAnimationFrame(frame);
    };
    const io = new IntersectionObserver(([e]) => e.isIntersecting && setNear(true), { rootMargin: "100% 0px" });
    io.observe(root);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
    };
  }, []);

  return (
    <section ref={rootRef} className="fq-dbox" style={{ "--world": me.world } as React.CSSProperties} aria-label={`${me.title} 마무리`}>
      <div className="fq-dbox-stage">
        {near ? <BoxFinale state={state} solo={solo} /> : null}
        <div className="fq-dbox-copy">
          <span>FANQ BOX</span>
          <h2>
            마지막 한 칸,
            <br />
            {me.object}
          </h2>
          <p>공연, 드롭, 팬미팅, 경매를 FANQ 한곳에서 이용하세요.</p>
        </div>
        <div className={`fq-dbox-end${chapter === 1 ? " is-on" : ""}`}>
          <h3>SEE YOU ON STAGE</h3>
          <nav aria-label="다른 무대로">
            {DOMAINS.filter((d) => d.key !== domain).map((d) => (
              <Link key={d.key} href={d.href} tabIndex={chapter === 1 ? 0 : -1} style={{ "--world": d.world } as React.CSSProperties}>
                {d.title}
                <i aria-hidden="true">→</i>
              </Link>
            ))}
          </nav>
        </div>
        <ChapterHud names={["담기", "닫힘"]} index={chapter} />
      </div>
    </section>
  );
}
