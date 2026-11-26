"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useState } from "react";
import LoadError from "@/components/LoadError";
import Tabs from "@/components/Tabs";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { IssuedTicketPage } from "@/lib/types";
import { seatText, ticketStatus } from "./status";

type F = "ALL" | "ISSUED" | "ENDED";

export default function TicketsPage() {
  const { profile, ready } = useAuth();
  if (!ready) return <div className="fq-skel" style={{ height: 300 }} />;
  if (!profile)
    return (
      <div className="fq-card is-pad fq-empty">
        <LineArt kind="ticket" />
        <h2>로그인하면 내 티켓을 볼 수 있어요</h2>
        <Link className="fq-btn is-grad" href="/login?next=%2Fme%2Ftickets">
          로그인
        </Link>
      </div>
    );
  return <Tickets />;
}

function Tickets() {
  const [f, setF] = useState<F>("ALL");
  const { data, error } = useLoad(() => api<IssuedTicketPage>("/api/tickets?size=100"));
  const all = data?.content ?? [];
  const match = (x: F, s: string) => x === "ALL" || (x === "ISSUED" ? s === "ISSUED" : s !== "ISSUED");
  const list = all.filter((t) => match(f, t.status));
  const count = (x: F) => (data ? all.filter((t) => match(x, t.status)).length : undefined);

  return (
    <>
      <Link href="/me" className="fq-back">
        ← 마이페이지
      </Link>
      <p className="fq-lead">결제를 마친 공연 티켓이에요. 좌석 한 개에 티켓 한 장이 발급돼요.</p>
      <Tabs
        label="티켓 상태 필터"
        value={f}
        onChange={setF}
        items={[
          { value: "ALL", label: "전체", count: count("ALL") },
          { value: "ISSUED", label: "사용 가능", count: count("ISSUED") },
          { value: "ENDED", label: "사용, 취소", count: count("ENDED") },
        ]}
      />
      <div className="fq-section" style={{ marginTop: 22 }}>
        {error ? (
          <LoadError error={error} backHref="/me" />
        ) : !data ? (
          <div className="fq-skel" style={{ height: 240 }} />
        ) : list.length === 0 ? (
          <div className="fq-card is-pad fq-empty">
            <LineArt kind="ticket" />
            <p>{all.length ? "해당하는 티켓이 없어요." : "아직 발급된 티켓이 없어요."}</p>
            {all.length ? null : (
              <Link className="fq-btn is-ghost" href="/concerts">
                공연 보러 가기
              </Link>
            )}
          </div>
        ) : (
          <ul className="fq-orders">
            {list.map((t, i) => {
              const s = ticketStatus(t.status);
              return (
                <li key={t.ticketId} className="fq-card fq-order" data-status={t.status} style={{ animationDelay: `${i * 60}ms` }}>
                  <div className="fq-order-b">
                    <div className="fq-row">
                      <span className={`fq-chip ${s.tone}`}>{s.label}</span>
                      <span className="fq-chip">{t.seat.grade}</span>
                    </div>
                    <h3>{t.concertTitle}</h3>
                    <small>
                      {dateTime(t.startsAt)}, {seatText(t.seat)}, {won(t.priceAmount)}
                    </small>
                  </div>
                  <div className="fq-order-r">
                    <Link className="fq-btn is-ghost is-sm" href={`/me/tickets/${t.ticketId}`}>
                      티켓 보기
                    </Link>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
        {data && data.totalElements > all.length ? <p className="fq-muted" style={{ marginTop: 12 }}>최근 {all.length}장만 보여 드려요. 전체 {data.totalElements}장</p> : null}
      </div>
    </>
  );
}
