"use client";

import { useRef, useState } from "react";
import ChapterHud from "@/components/chapter/ChapterHud";
import StageScene, { type StageControl } from "@/components/stage/StageScene";
import { chapterAt, ramp, smooth } from "@/lib/chapters";
import { dateTime } from "@/lib/format";
import { useScrollProgress } from "@/lib/scroll";
import type { PerformanceInfo } from "@/lib/types";
import "./[id]/concert.css";

const STEPS = [
  { no: "01", title: "회차를 고르고", body: "날짜와 시간을 고르면 그 회차의 좌석 배치도가 열려요. 1인당 살 수 있는 매수는 회차마다 달라요." },
  { no: "02", title: "좌석을 잡고", body: "원하는 자리를 직접 눌러 잡아요. 잡은 좌석은 결제 기한까지 다른 사람이 가져갈 수 없어요." },
  { no: "03", title: "기한 안에 결제", body: "잡은 좌석에는 결제 기한이 붙어요. 시간이 줄어드는 동안 결제하면 확정이에요." },
  { no: "04", title: "티켓이 나와요", body: "확정된 티켓은 마이페이지의 주문과 참여 내역에서 확인할 수 있어요." },
];
/** 첫 화면 장 길이 비율: 암전, 조명, 객석, 무대로, 첫 공연 */
const HERO_SPANS = [0.8, 1, 1, 1.2, 1.2];
const HERO_NAMES = ["암전", "조명", "객석", "무대로", "첫 공연"];

/**
 * 공연 목록 앞의 긴 스크롤 연출. 대표 공연 하나로 무대를 연다.
 * 1) 암전에서 조명, 객석, LED 화면이 차례로 켜지고 첫 공연 날짜가 뜬다.
 * 2) 예매 순서 네 단계를 고정된 화면에서 한 장씩 보여 준다. 3) 큰 글이 한 줄씩 드러난다.
 */
export default function ConcertShow({
  seed,
  name,
  onSale,
  list,
  skipTo,
}: {
  seed: number;
  name: { head: string; rest: string } | null;
  onSale: boolean;
  /** 대표 공연의 회차. 시작 시각 순서 */
  list: PerformanceInfo[];
  skipTo: string;
}) {
  const first = list[0];
  const heroRef = useRef<HTMLElement>(null);
  const featRef = useRef<HTMLElement>(null);
  const revealRef = useRef<HTMLElement>(null);
  const timerRef = useRef<HTMLElement>(null);
  const stage = useRef<StageControl>({ beams: 0, crowd: 0, screen: 0 });
  const [power, setPower] = useState(0);
  const [heroCh, setHeroCh] = useState(0);
  const [step, setStep] = useState(0);
  // 첫 화면: 장마다 조명, 객석, LED 화면을 차례로 켜고 무대로 다가간다
  useScrollProgress(heroRef, (p) => {
    const ch = chapterAt(p, HERO_SPANS);
    const t = ch.local;
    const at = (i: number) => (ch.index > i ? 1 : ch.index === i ? smooth(t) : 0);
    stage.current = { beams: at(1), crowd: at(2), screen: at(3) };
    const el = heroRef.current!;
    el.style.setProperty("--zoom", at(3).toFixed(3));
    el.style.setProperty("--late", (ch.index === 4 ? smooth(ramp(t, 0, 0.5)) : 0).toFixed(3));
    el.style.setProperty("--copy", (ch.index < 3 ? 1 : ch.index === 3 ? 1 - smooth(ramp(t, 0, 0.5)) : 0).toFixed(3));
    setPower(Math.round(at(3) * 10) / 10);
    setHeroCh(ch.index);
  });
  // 예매 순서: 단계마다 그 안의 진행률(--s)로 그림이 움직인다
  useScrollProgress(featRef, (p) => {
    const ch = chapterAt(p, STEPS.map(() => 1));
    featRef.current!.style.setProperty("--s", smooth(ramp(ch.local, 0.1, 0.8)).toFixed(3));
    if (ch.index === 2 && timerRef.current) {
      const sec = Math.round(299 - ramp(ch.local, 0.1, 0.9) * 47);
      timerRef.current.textContent = `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
    }
    setStep(ch.index);
  });
  useScrollProgress(revealRef, undefined, "enter");

  return (
    <div className="fq-cd">
      {/* 1. 영화처럼 열리는 첫 화면. 스크롤하면 무대가 다가오고 조명이 밝아진다 */}
      <section ref={heroRef} className="fq-cd-hero">
        <div className="fq-cd-hero-stage">
          <div className="fq-cd-hero-scene">
            <StageScene seed={seed} intensity={power} control={stage} screenText={name?.head} />
          </div>
          <div className="fq-cd-hero-copy">
            <div className="fq-row">
              <span className={`fq-chip${onSale ? " is-mint" : ""}`}>{onSale ? "예매 중" : "예매 예정"}</span>
              <span className="fq-cd-kicker">LIVE CONCERT</span>
            </div>
            <h2 className="fq-cd-name">{name?.head ?? "…"}</h2>
            <p className="fq-cd-sub">{name?.rest}</p>
            <div className="fq-row">
              <a className="fq-btn is-grad" href={skipTo}>
                공연 고르기 ↓
              </a>
              {first ? <span className="fq-cd-first fq-num">{dateTime(first.startsAt)} 첫 공연</span> : null}
            </div>
          </div>
          <div className="fq-cd-hero-late" aria-hidden="true">
            <span className="fq-cd-kicker">THE SHOW BEGINS</span>
            <b>{first ? new Date(first.startsAt).toLocaleDateString("en-US", { month: "short", day: "2-digit" }).toUpperCase() : "SOON"}</b>
            <small>{first ? dateTime(first.startsAt) : "회차가 아직 정해지지 않았어요"}</small>
          </div>
          <span className="fq-cd-hint" aria-hidden="true">
            SCROLL
          </span>
          <ChapterHud names={HERO_NAMES} index={heroCh} skipTo={skipTo} />
        </div>
      </section>

      {/* 2. 고정된 화면에서 오른쪽 설명 카드가 한 장씩 바뀐다 */}
      <section ref={featRef} className="fq-cd-feat" aria-label="예매 순서">
        <div className="fq-cd-feat-stage">
          <div className={`fq-cd-visual is-step-${step}`} aria-hidden="true">
            <div className="fq-cd-v fq-cd-v-dates">
              {(list.length ? list : [null, null, null]).slice(0, 4).map((p, i) => {
                const d = p ? new Date(p.startsAt) : null;
                return (
                  <span key={i} className={i === 0 ? "is-on" : undefined}>
                    <b>{d ? d.getDate() : "--"}</b>
                    <small>{d ? d.toLocaleDateString("ko-KR", { month: "short", weekday: "short" }) : "준비 중"}</small>
                  </span>
                );
              })}
            </div>
            <div className="fq-cd-v fq-cd-v-seats">
              {Array.from({ length: 84 }, (_, i) => (
                <i key={i} className={[41, 42, 43].includes(i) ? "is-mine" : i % 7 === 3 || i % 11 === 0 ? "is-taken" : undefined} style={{ "--i": i } as React.CSSProperties} />
              ))}
              <span className="fq-cd-v-stagebar">STAGE</span>
            </div>
            <div className="fq-cd-v fq-cd-v-pay">
              <svg viewBox="0 0 120 120">
                <circle cx="60" cy="60" r="52" />
                <circle cx="60" cy="60" r="52" className="is-run" />
              </svg>
              <b ref={timerRef} className="fq-num">
                04:59
              </b>
              <small>결제 기한</small>
            </div>
            <div className="fq-cd-v fq-cd-v-ticket">
              <span className="fq-cd-v-slot" />
              <div className="fq-cd-v-ticket-clip">
                <div className="fq-cd-v-ticket-paper">
                  <span className="fq-cd-kicker">ADMIT ONE</span>
                  <b>{name?.head ?? "LIVE"}</b>
                  <small>{first ? dateTime(first.startsAt) : "회차 준비 중"}</small>
                  <i>좌석 예시 A구역 3열</i>
                </div>
              </div>
            </div>
          </div>
          <ol className="fq-cd-steps">
            {STEPS.map((s, i) => (
              <li key={s.no} className={i === step ? "is-on" : i < step ? "is-done" : undefined}>
                <span className="fq-num">{s.no}</span>
                <h3>{s.title}</h3>
                <p>{s.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* 4. 스크롤에 맞춰 한 줄씩 드러나는 큰 글 */}
      <section ref={revealRef} className="fq-cd-reveal" aria-label="FANQ 라이브">
        <p>
          <span>Made for</span>
          <span>the stage,</span>
          <span>
            <em>tuned by the crowd.</em>
          </span>
        </p>
      </section>

    </div>
  );
}
