"use client";

import Link from "next/link";
import { countdown, dateTime, msLeft, won } from "@/lib/format";
import { useNow } from "@/lib/hooks";
import type { AuctionSummary, ConcertDetail, DropItem, FanMeetingSummary } from "@/lib/types";

import Poster from "./Poster";
import "./Cards.css";

export const firstWord = (t: string) => t.replace(/[‘’'"]/g, "").split(/\s+/)[0];

export function DropStatusChip({ item, now }: { item: DropItem; now: number }) {
  if ((item.saleStatus === "ON_SALE" || item.saleStatus === "OPEN") && msLeft(item.closesAt, now) > 0) return <span className="fq-chip is-live">판매 중</span>;
  if (item.saleStatus === "UPCOMING" || item.saleStatus === "SCHEDULED" || msLeft(item.opensAt, now) > 0)
    return <span className="fq-chip">오픈 {countdown(item.opensAt, now)}</span>;
  if (item.saleStatus === "SOLD_OUT") return <span className="fq-chip is-mute">품절</span>;
  return <span className="fq-chip is-mute">판매 종료</span>;
}

export function DropCard({ item }: { item: DropItem }) {
  const now = useNow();
  return (
    <Link href={`/drops/detail?id=${item.dropId}`} className="fq-card fq-ccard">
      <Poster seed={item.dropId + 1} word={firstWord(item.title)} kicker={`DROP #${String(item.dropId).padStart(3, "0")}`} imageUrl={item.imageUrl} ratio="1 / 1" />
      <div className="fq-ccard-b">
        <DropStatusChip item={item} now={now} />
        <h3>{item.title}</h3>
        <p className="fq-muted">{dateTime(item.opensAt)} 오픈</p>
      </div>
    </Link>
  );
}

export function AuctionStatusChip({ a, now }: { a: { status: string; startsAt: string; endsAt: string }; now: number }) {
  if (a.status === "OPEN") {
    const left = msLeft(a.endsAt, now);
    return <span className={`fq-chip is-live${left < 15 * 60_000 ? " is-urgent" : ""}`}>LIVE {countdown(a.endsAt, now)}</span>;
  }
  if (a.status === "SCHEDULED") return <span className="fq-chip">시작까지 {countdown(a.startsAt, now)}</span>;
  return <span className="fq-chip is-mute">종료</span>;
}

export function AuctionCard({ a }: { a: AuctionSummary }) {
  const now = useNow();
  return (
    <Link href={`/auctions/detail?id=${a.auctionId}`} className="fq-card fq-ccard">
      <Poster seed={a.auctionId + 3} word={a.itemName} kicker={`LOT ${String(a.auctionId).padStart(2, "0")}`} ratio="4 / 3" />
      <div className="fq-ccard-b">
        <AuctionStatusChip a={a} now={now} />
        <h3>{a.title}</h3>
        <p className="fq-muted">
          시작가 <b className="fq-num">{won(a.startingPriceAmount)}</b>
        </p>
      </div>
    </Link>
  );
}

export function ConcertCard({ c }: { c: ConcertDetail }) {
  return (
    <Link href={`/concerts/detail?id=${c.concertId}`} className="fq-card fq-ccard">
      <Poster seed={c.concertId} word={firstWord(c.title)} kicker={c.status === "ON_SALE" ? "ON SALE" : "COMING SOON"} />
      <div className="fq-ccard-b">
        <span className={`fq-chip${c.status === "ON_SALE" ? " is-mint" : ""}`}>{c.status === "ON_SALE" ? "예매 중" : "예매 예정"}</span>
        <h3>{c.title}</h3>
      </div>
    </Link>
  );
}

export const FM_STATUS: Record<string, { label: string; tone: string }> = {
  SCHEDULED: { label: "응모 예정", tone: "" },
  CANCELLED: { label: "취소됨", tone: "is-mute" },
  OPEN: { label: "응모 중", tone: "is-live" },
  CLOSED: { label: "응모 마감", tone: "is-mute" },
  DRAWING: { label: "추첨 중", tone: "is-warn" },
  PAYMENT_IN_PROGRESS: { label: "당첨자 결제 중", tone: "is-mint" },
  RECRUITMENT_COMPLETED: { label: "모집 완료", tone: "is-mute" },
};

export function FanMeetingCard({ f }: { f: FanMeetingSummary }) {
  const now = useNow();
  const s = FM_STATUS[f.status] ?? { label: f.status, tone: "" };
  return (
    <Link href={`/fan-meetings/detail?id=${f.eventId}`} className="fq-card fq-ccard">
      <Poster seed={f.eventId + 4} word={firstWord(f.title)} kicker="FAN MEETING" ratio="4 / 5" />
      <div className="fq-ccard-b">
        <div className="fq-row">
          <span className={`fq-chip ${s.tone}`}>{s.label}</span>
          {f.status === "OPEN" ? <span className="fq-chip is-warn">마감 {countdown(f.entryClosesAt ?? "", now)}</span> : null}
          {f.status === "SCHEDULED" && f.entryOpensAt === null ? <span className="fq-chip is-warn">게릴라, 곧 공개</span> : null}
        </div>
        <h3>{f.title}</h3>
        <p className="fq-muted">
          당첨 {f.winnerLimit}명, {f.feeAmount ? won(f.feeAmount) : "무료"}
        </p>
      </div>
    </Link>
  );
}

export function CardSkeletons({ n = 4 }: { n?: number }) {
  return (
    <div className="fq-grid">
      {Array.from({ length: n }, (_, i) => (
        <div key={i} className="fq-skel" style={{ height: 360 }} />
      ))}
    </div>
  );
}
