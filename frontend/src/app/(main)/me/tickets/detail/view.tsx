"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import LoadError from "@/components/LoadError";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { IssuedTicket } from "@/lib/types";
import { seatText, ticketStatus } from "../status";

export default function TicketView({ id }: { id: number }) {
  const { profile, ready } = useAuth();
  const { data: t, error } = useLoad(() => api<IssuedTicket>(`/api/tickets/${id}`), [id, profile?.memberId]);
  if (!ready) return <div className="fq-skel" style={{ height: 300 }} />;
  if (!profile)
    return (
      <div className="fq-card is-pad fq-empty">
        <LineArt kind="ticket" />
        <h2>로그인하면 티켓을 볼 수 있어요</h2>
        <Link className="fq-btn is-grad" href={`/login?next=${encodeURIComponent(`/me/tickets/detail?id=${id}`)}`}>
          로그인
        </Link>
      </div>
    );
  if (error) return <LoadError error={error} backHref="/me/tickets" />;
  if (!t) return <div className="fq-skel" style={{ height: 300 }} />;
  const s = ticketStatus(t.status);
  return (
    <>
      <Link href="/me/tickets" className="fq-back">
        ← 내 티켓
      </Link>
      <section className="fq-card is-pad" style={{ marginTop: 12 }}>
        <div className="fq-between">
          <span className="fq-eyebrow">TICKET {t.ticketNo}</span>
          <span className={`fq-chip ${s.tone}`}>{s.label}</span>
        </div>
        <h2 style={{ marginTop: 10 }}>{t.concertTitle}</h2>
        <p className="fq-muted">{dateTime(t.startsAt)}</p>
        <dl className="fq-kv" style={{ marginTop: 16 }}>
          <dt>좌석</dt>
          <dd>
            {t.seat.grade}석, {seatText(t.seat)}
          </dd>
          <dt>가격</dt>
          <dd className="fq-num">{won(t.priceAmount)}</dd>
          <dt>발급</dt>
          <dd>{dateTime(t.issuedAt)}</dd>
          {t.usedAt ? (
            <>
              <dt>사용</dt>
              <dd>{dateTime(t.usedAt)}</dd>
            </>
          ) : null}
          {t.cancelledAt ? (
            <>
              <dt>취소</dt>
              <dd>{dateTime(t.cancelledAt)}</dd>
            </>
          ) : null}
          <dt>예약, 주문</dt>
          <dd className="fq-num">
            #{t.reservationId}, #{t.orderId}
          </dd>
        </dl>
        <div className="fq-row" style={{ marginTop: 18 }}>
          <Link className="fq-btn is-ghost" href={`/concerts/detail?id=${t.concertId}`}>
            공연 정보
          </Link>
        </div>
      </section>
    </>
  );
}
