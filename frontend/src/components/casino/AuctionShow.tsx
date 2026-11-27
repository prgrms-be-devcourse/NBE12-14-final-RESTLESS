"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import ChapterHud from "@/components/chapter/ChapterHud";
import { chapterAt, mix, ramp, smooth } from "@/lib/chapters";
import { countdown, won } from "@/lib/format";
import type { AuctionDetail } from "@/lib/types";
import Chip from "./Chip";
import "./show.css";

const TITLE = "READ THE TABLE.";
const POCKETS = 18;

/**
 * 경매 목록 앞의 긴 스크롤 연출.
 * 1) 칩이 떨어져 테이블 위에서 돌다 멈추고, 제목 글자가 하나씩 떨어진다.
 * 2) 스크롤로 룰렛을 돌리면 진행 중인 경매 번호 칸에서 차례로 멈춘다.
 * 3) 진행 중인 경매마다 한 장씩, 금액이 시작가에서 현재가까지 굴러 올라간다.
 * 4) 마감 직전 입찰이 들어오면 시간이 늘어나는 규칙을 시계로 보여 준다.
 */
export default function AuctionShow({ live, now, listId }: { live: AuctionDetail[]; now: number; listId: string }) {
  const rootRef = useRef<HTMLElement>(null);
  const priceRef = useRef<HTMLElement>(null);
  const clockRef = useRef<HTMLElement>(null);
  const [chapter, setChapter] = useState(0);
  const spans = [1.2, 1.6, ...live.map(() => 1.4), 1.3];
  const LOT0 = 2;
  const lotIdx = chapter >= LOT0 && chapter < LOT0 + live.length ? chapter - LOT0 : -1;
  const lot = lotIdx >= 0 ? live[lotIdx] : null;
  // 룰렛 칸 중 진행 중인 경매가 놓일 자리
  const pocketOf = (i: number) => (3 + i * 7) % POCKETS;

  useEffect(() => {
    const root = rootRef.current!;
    let raf = 0;
    let target = 0;
    let shown = 0;
    let cur = -1;
    let visible = true;
    const read = () => {
      const r = root.getBoundingClientRect();
      const span = root.offsetHeight - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -r.top / span)) : 0;
    };
    const frame = () => {
      raf = 0;
      shown += (target - shown) * 0.12;
      if (Math.abs(target - shown) < 0.0003) shown = target;
      const ch = chapterAt(shown, spans);
      const t = ch.local;
      root.style.setProperty("--p", shown.toFixed(4));

      // 1장: 칩 낙하와 제목
      const fall = ch.index === 0 ? smooth(ramp(t, 0, 0.45)) : 1;
      const bounce = ch.index === 0 ? Math.sin(ramp(t, 0.45, 0.7) * Math.PI) * (1 - ramp(t, 0.45, 0.7)) : 0;
      root.style.setProperty("--fall", fall.toFixed(3));
      root.style.setProperty("--bounce", bounce.toFixed(3));
      root.style.setProperty("--letters", (ch.index === 0 ? ramp(t, 0.2, 0.9) : 1).toFixed(3));

      // 2장: 룰렛. 진행 중 경매 칸마다 멈춰 선다
      let wheel = 0;
      let wheelOn = ch.index === 1 ? smooth(ramp(t, 0, 0.15)) * (1 - smooth(ramp(t, 0.9, 1))) : 0;
      if (ch.index === 1) {
        const stops = live.length ? live.map((_, i) => pocketOf(i)) : [0];
        const x = t * stops.length;
        const k = Math.min(stops.length - 1, Math.floor(x));
        const f = smooth(ramp(x - k, 0.1, 0.6));
        const from = k === 0 ? -360 : -(stops[k - 1] * (360 / POCKETS)) - 360 * k;
        const to = -(stops[k] * (360 / POCKETS)) - 360 * (k + 1);
        wheel = mix(from, to, f);
        root.style.setProperty("--pocket", String(f > 0.98 ? stops[k] : -1));
      } else if (ch.index > 1) wheelOn = 0;
      root.style.setProperty("--wheel", `${wheel.toFixed(2)}deg`);
      root.style.setProperty("--wheelOn", wheelOn.toFixed(3));

      // 3장: 경매별 금액이 시작가에서 현재가로 오른다
      const li = ch.index - LOT0;
      if (li >= 0 && li < live.length && priceRef.current) {
        const a = live[li];
        const end = a.highestBid?.amount ?? a.startingPriceAmount;
        const v = Math.round(mix(a.startingPriceAmount, end, smooth(ramp(t, 0.15, 0.7))) / 1000) * 1000;
        priceRef.current.textContent = v.toLocaleString("ko-KR");
      }
      root.style.setProperty("--lot", (li >= 0 && li < live.length ? smooth(ramp(t, 0, 0.15)) * (1 - smooth(ramp(t, 0.88, 1))) : 0).toFixed(3));

      // 4장: 연장 규칙. 10초에서 줄다가 입찰이 들어오면 60초가 더해진다
      if (ch.index === spans.length - 1 && clockRef.current) {
        const bid = t >= 0.55;
        const sec = bid ? Math.round(mix(61, 55, ramp(t, 0.55, 1))) : Math.round(mix(10, 1, ramp(t, 0.05, 0.55)));
        clockRef.current.textContent = `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
        root.style.setProperty("--ring", Math.min(1, sec / 61).toFixed(3));
        root.classList.toggle("is-bid", bid);
      }
      root.style.setProperty("--rule", (ch.index === spans.length - 1 ? smooth(ramp(t, 0, 0.15)) : 0).toFixed(3));

      if (ch.index !== cur) {
        cur = ch.index;
        setChapter(ch.index);
      }
      if (shown !== target && visible) raf = requestAnimationFrame(frame);
    };
    const kick = () => {
      if (!raf) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      read();
      kick();
    };
    const io = new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      if (visible) kick();
    });
    io.observe(root);
    read();
    shown = target;
    kick();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
    };
    // live 목록이 바뀌면 장 구성이 바뀐다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live.map((a) => a.auctionId).join(",")]);

  const total = spans.reduce((a, b) => a + b, 0);
  const names = ["테이블", "룰렛", ...live.map((_, i) => `LOT ${String(i + 1).padStart(2, "0")}`), "연장 규칙"];

  return (
    <section ref={rootRef} className="fq-show" style={{ height: `${100 + total * 85}vh` }} aria-label="라이브 경매 소개">
      <div className="fq-show-stage">
        {/* 1장 */}
        <div className="fq-show-open">
          <h2 className="fq-show-title" aria-label={TITLE}>
            {/* 단어 단위로 묶어 줄바꿈이 글자 중간에서 일어나지 않게 한다 */}
            {TITLE.split(" ").map((word, wi, words) => {
              const offset = words.slice(0, wi).join(" ").length + (wi ? 1 : 0);
              return (
                <span key={wi} className={`fq-show-word${wi === words.length - 1 ? " is-last" : ""}`} aria-hidden="true">
                  {word.split("").map((ch, ci) => (
                    <span key={ci} style={{ "--i": offset + ci } as React.CSSProperties}>
                      {ch}
                    </span>
                  ))}
                </span>
              );
            })}
          </h2>
          <p className="fq-show-sub">공연에서 쓰인 물품을 실시간 입찰로 판매해요.</p>
          <div className="fq-show-chip">
            <Chip seed={0} top="LIVE" big={String(live.length).padStart(2, "0")} size={240} />
          </div>
        </div>

        {/* 2장 */}
        <div className="fq-show-wheel" aria-hidden="true">
          <i className="fq-show-pointer" />
          <svg viewBox="-110 -110 220 220">
            <g className="fq-show-wheel-spin">
              {Array.from({ length: POCKETS }, (_, i) => {
                const a0 = (i / POCKETS) * Math.PI * 2 - Math.PI / 2;
                const a1 = ((i + 1) / POCKETS) * Math.PI * 2 - Math.PI / 2;
                const liveAt = live.findIndex((_, k) => pocketOf(k) === i);
                const path = `M0 0 L${Math.cos(a0) * 100} ${Math.sin(a0) * 100} A100 100 0 0 1 ${Math.cos(a1) * 100} ${Math.sin(a1) * 100} Z`;
                const am = (a0 + a1) / 2;
                return (
                  <g key={i} className={liveAt >= 0 ? "is-live" : undefined} data-pocket={i}>
                    <path d={path} className={i % 2 ? "is-red" : "is-black"} />
                    <text x={Math.cos(am) * 82} y={Math.sin(am) * 82} transform={`rotate(${(am * 180) / Math.PI + 90} ${Math.cos(am) * 82} ${Math.sin(am) * 82})`}>
                      {liveAt >= 0 ? `L${liveAt + 1}` : (i * 7) % 36}
                    </text>
                  </g>
                );
              })}
              <circle r="56" className="fq-show-wheel-hub" />
              <circle r="18" className="fq-show-wheel-cap" />
            </g>
          </svg>
          <p className="fq-show-wheel-cap-text">진행 중인 경매를 차례로 보여 드려요.</p>
        </div>

        {/* 3장 */}
        <div className="fq-show-lot">
          {lot ? (
            <>
              <span className="fq-show-kicker">LOT {String(lotIdx + 1).padStart(2, "0")} NOW ON THE TABLE</span>
              <h3>{lot.title}</h3>
              <div className="fq-show-price fq-num">
                <b ref={priceRef}>{lot.startingPriceAmount.toLocaleString("ko-KR")}</b>
                <small>원</small>
              </div>
              <p>
                시작가 {won(lot.startingPriceAmount)}에서 지금 {lot.highestBid ? `${lot.highestBid.nickname} 님이 앞서고 있어요.` : "첫 입찰을 기다리고 있어요."} 마감까지 <b className="fq-num">{countdown(lot.endsAt, now)}</b>
              </p>
              <Link href={`/auctions/detail?id=${lot.auctionId}`} className="fq-show-cta">
                이 경매 보기 →
              </Link>
            </>
          ) : null}
        </div>

        {/* 4장 */}
        <div className="fq-show-rule" aria-hidden="true">
          <svg viewBox="0 0 120 120">
            <circle cx="60" cy="60" r="52" />
            <circle cx="60" cy="60" r="52" className="is-run" />
          </svg>
          <b ref={clockRef} className="fq-num">
            00:10
          </b>
          <span className="fq-show-rule-flash">입찰! +60초</span>
          <p>마감 직전에 입찰이 들어오면 마감 시각이 늘어나요.</p>
        </div>

        <ChapterHud names={names} index={chapter} skipTo={listId} />
      </div>
    </section>
  );
}
