"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import Odometer from "@/components/casino/Odometer";
import LoadError from "@/components/LoadError";
import StageScene from "@/components/stage/StageScene";
import { api } from "@/lib/api";
import { countdown, dateTime, msLeft } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import type { ConcertDetail, PerformanceInfo } from "@/lib/types";
import "./concert.css";

/** 제목에서 크게 쓸 이름과 나머지를 나눈다. 앞의 [표시]는 뺀다 */
function splitTitle(title: string) {
  const clean = title.replace(/^\[[^\]]*\]\s*/, "");
  const [head, ...rest] = clean.split(/\s+/);
  return { head: head || title, rest: rest.join(" ") };
}

export default function ConcertView({ id }: { id: number }) {
  const now = useNow();
  const concert = useLoad(() => api<ConcertDetail>(`/api/concerts/${id}`), [id]);
  const perfs = useLoad(() => api<PerformanceInfo[]>(`/api/concerts/${id}/performances`), [id]);
  const c = concert.data;


  if (concert.error) return <LoadError error={concert.error} backHref="/concerts" />;

  const list = [...(perfs.data ?? [])].sort((a, b) => +new Date(a.startsAt) - +new Date(b.startsAt));
  const first = list[0];
  const name = c ? splitTitle(c.title) : null;
  const daysToShow = first ? Math.max(0, Math.ceil(msLeft(first.startsAt, now) / 86_400_000)) : null;
  const openNow = first ? msLeft(first.bookingOpensAt, now) <= 0 && msLeft(first.bookingClosesAt, now) > 0 : false;

  return (
    <div className="fq-cd">
      <Link href="/concerts" className="fq-back">
        ← 공연 목록
      </Link>

      {/* 1. 짧은 머리말. 긴 연출은 공연 목록에 있다 */}
      <section className="fq-cd-top">
        <div className="fq-cd-top-scene">
          <StageScene seed={id} intensity={0.4} />
        </div>
        <div className="fq-cd-top-copy">
          <div className="fq-row">
            <span className={`fq-chip${c?.status === "ON_SALE" || openNow ? " is-mint" : ""}`}>{c?.status === "ON_SALE" || openNow ? "예매 중" : "예매 예정"}</span>
            <span className="fq-cd-kicker">LIVE CONCERT</span>
          </div>
          <h2 className="fq-cd-name">{name?.head ?? "…"}</h2>
          <p className="fq-cd-sub">{name?.rest}</p>
          {first ? <span className="fq-cd-first fq-num">{dateTime(first.startsAt)} 첫 공연</span> : null}
        </div>
      </section>


      {/* 2. 숫자가 굴러 올라가는 공연 정보 */}
      <section className="fq-cd-stats" aria-label="공연 정보">
        <Stat label="회차" value={list.length} suffix="회" empty={!perfs.data} />
        <Stat label="1인 최대" value={first?.maxTicketsPerUser ?? 0} suffix="매" empty={!first} />
        <Stat label="첫 공연까지" value={daysToShow ?? 0} suffix="일" empty={daysToShow === null} />
        <div className="fq-cd-stat">
          <small>예매</small>
          <b className="fq-num">{!first ? "준비 중" : openNow ? "오픈" : msLeft(first.bookingOpensAt, now) > 0 ? countdown(first.bookingOpensAt, now) : "마감"}</b>
        </div>
      </section>

      {/* 3. 회차 선택 */}
      <section id="perfs" className="fq-cd-perfs">
        <span className="fq-cd-kicker">SELECT A SHOW</span>
        <h2>회차 선택</h2>
        {perfs.error ? (
          <LoadError error={perfs.error} />
        ) : !perfs.data ? (
          <div className="fq-skel" style={{ height: 120 }} />
        ) : list.length === 0 ? (
          <div className="fq-card is-pad fq-empty">
            <LineArt kind="ticket" />
            <p>아직 열린 회차가 없어요. 회차가 정해지면 여기에서 예매할 수 있어요.</p>
          </div>
        ) : (
          <ul className="fq-cd-tickets">
            {list.map((p, i) => {
              const notYet = msLeft(p.bookingOpensAt, now) > 0;
              const closed = msLeft(p.bookingClosesAt, now) <= 0;
              const d = new Date(p.startsAt);
              return (
                <li key={p.performanceId} className={`fq-cd-ticket${closed ? " is-closed" : ""}`} style={{ animationDelay: `${i * 80}ms` }}>
                  <div className="fq-cd-ticket-date">
                    <b>{d.getDate()}</b>
                    <span>{d.toLocaleDateString("ko-KR", { month: "short", weekday: "short" })}</span>
                  </div>
                  <div className="fq-cd-ticket-info">
                    <strong>{dateTime(p.startsAt)}</strong>
                    <span>{notYet ? `예매 오픈까지 ${countdown(p.bookingOpensAt, now)}` : closed ? "예매 마감" : `1인 ${p.maxTicketsPerUser}매까지`}</span>
                  </div>
                  <div className="fq-cd-ticket-cta">
                    {notYet || closed ? (
                      <button className="fq-btn is-ghost is-sm" disabled>
                        {notYet ? "오픈 대기" : "마감"}
                      </button>
                    ) : (
                      <Link className="fq-btn is-grad" href={`/performances/${p.performanceId}?max=${p.maxTicketsPerUser}&concert=${id}`}>
                        좌석 선택
                      </Link>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}

/** 화면에 들어오면 0부터 굴러 올라가는 숫자 */
function Stat({ label, value, suffix, empty }: { label: string; value: number; suffix: string; empty: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(([e]) => {
      if (e.isIntersecting) {
        setSeen(true);
        io.disconnect();
      }
    }, { threshold: 0.4 });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <div ref={ref} className="fq-cd-stat">
      <small>{label}</small>
      {empty ? <b className="fq-num">준비 중</b> : seen ? <Odometer value={value} suffix={suffix} className="fq-num" /> : <b className="fq-num">0{suffix}</b>}
    </div>
  );
}
