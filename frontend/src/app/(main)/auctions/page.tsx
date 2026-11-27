"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import DomainBox from "@/components/finale/DomainBox";
import { useState } from "react";
import { CardSkeletons } from "@/components/Cards";
import AuctionShow from "@/components/casino/AuctionShow";
import { IntroGate } from "@/lib/intro";
import Chip from "@/components/casino/Chip";
import Odometer from "@/components/casino/Odometer";
import LoadError from "@/components/LoadError";
import Tabs from "@/components/Tabs";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { countdown, dateTime, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import type { AuctionDetail, AuctionPage, AuctionStatus, AuctionSummary, MyBidPage } from "@/lib/types";
import "./auctions.css";

type F = "ALL" | "MINE" | AuctionStatus;
const ORDER: Record<AuctionStatus, number> = { OPEN: 0, SCHEDULED: 1, CLOSED: 2 };

export default function AuctionsPage() {
  const now = useNow();
  const [f, setF] = useState<F>("ALL");
  const { profile } = useAuth();
  const { data, error } = useLoad(() => api<AuctionPage>("/api/auctions?size=40").then((r) => r.items));
  // 목록 API에는 현재가가 없어서, 진행 중인 경매만 상세를 읽어 현재가를 보여 준다
  const liveIds = (data ?? []).filter((a) => a.status === "OPEN").slice(0, 8).map((a) => a.auctionId);
  const liveDetails = useLoad(
    () => Promise.all(liveIds.map((id) => api<AuctionDetail>(`/api/auctions/${id}`).catch(() => null))).then((list) => list.filter((d): d is AuctionDetail => !!d)),
    [liveIds.join(",")],
  );
  const prices = { data: Object.fromEntries((liveDetails.data ?? []).map((d) => [d.auctionId, d.highestBid?.amount ?? d.startingPriceAmount])) as Record<number, number> };
  const mine = useLoad(() => (profile ? api<MyBidPage>("/api/me/bids?size=100").then((r) => r.items) : Promise.resolve([])), [profile?.memberId]);
  const sorted = [...(data ?? [])].sort((a, b) => ORDER[a.status] - ORDER[b.status] || +new Date(a.endsAt) - +new Date(b.endsAt));
  const count = (x: F) => data?.filter((a) => x === "ALL" || a.status === x).length;

  return (
    <div className="fq-au">
      <IntroGate>{data && liveDetails.data ? <AuctionShow live={liveDetails.data} now={now} listId="#auction-list" /> : <div className="fq-skel" style={{ height: "80vh" }} />}</IntroGate>

      <div id="auction-list" style={{ scrollMarginTop: 20, marginTop: 28 }} />
      <Tabs
        label="경매 필터"
        value={f}
        onChange={setF}
        items={[
          { value: "ALL", label: "전체", count: count("ALL") },
          { value: "OPEN", label: "LIVE", count: count("OPEN") },
          { value: "SCHEDULED", label: "예정", count: count("SCHEDULED") },
          { value: "CLOSED", label: "종료", count: count("CLOSED") },
          ...(profile ? [{ value: "MINE" as const, label: "내 입찰", count: mine.data?.length ?? 0 }] : []),
        ]}
      />

      <div className="fq-section" style={{ marginTop: 22 }}>
        {error ? (
          <LoadError error={error} />
        ) : f === "MINE" ? (
          mine.data ? (
            mine.data.length ? (
              <ul className="fq-au-rows">
                {mine.data.map((bid, i) => (
                  <li key={bid.auctionId}>
                    <Link className="fq-au-row" href={`/auctions/detail?id=${bid.auctionId}`} style={{ animationDelay: `${i * 70}ms` }}>
                      <span className="fq-au-lot">{String(i + 1).padStart(2, "0")}</span>
                      <Chip seed={bid.auctionId} top="BID" big={String(i + 1).padStart(2, "0")} size={64} still />
                      <div className="fq-au-main">
                        <span className={`fq-chip ${bid.status === "WON" ? "is-mint" : bid.status === "HIGHEST" ? "is-live" : "is-mute"}`}>
                          {bid.auctionStatus === "CLOSED" && bid.status === "OUTBID" ? "낙찰 실패" : bid.status === "HIGHEST" ? "최고 입찰" : bid.status === "WON" ? "낙찰" : "추월됨"}
                        </span>
                        <h3>{bid.title}</h3>
                        <small>{bid.itemName}</small>
                      </div>
                      <div className="fq-au-price">
                        <small>내 입찰가</small>
                        <b className="fq-num">{won(bid.amount)}</b>
                      </div>
                      <span className="fq-au-go" aria-hidden="true">→</span>
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="fq-muted">참여한 경매가 없어요.</p>
            )
          ) : (
            <CardSkeletons />
          )
        ) : data ? (
          <ul className="fq-au-rows">
            {sorted
              .filter((a) => f === "ALL" || a.status === f)
              .map((a, i) => (
                <li key={a.auctionId}>
                  <Row a={a} lot={String(sorted.indexOf(a) + 1).padStart(2, "0")} now={now} price={prices.data?.[a.auctionId]} delay={i * 70} />
                </li>
              ))}
            {sorted.filter((a) => f === "ALL" || a.status === f).length === 0 ? (
              <li className="fq-card is-pad fq-empty">
                <LineArt kind="chip" />
                <p>해당하는 경매가 없어요.</p>
              </li>
            ) : null}
          </ul>
        ) : (
          <CardSkeletons />
        )}
      </div>
      <DomainBox domain="auction" />
    </div>
  );
}

/** lot은 정렬된 전체 목록에서의 순번이다. 필터를 바꿔도 같은 경매는 같은 번호를 유지한다 */
function Row({ a, lot, now, price, delay }: { a: AuctionSummary; lot: string; now: number; price?: number; delay: number }) {
  const urgent = a.status === "OPEN" && msLeft(a.endsAt, now) < 15 * 60_000;
  return (
    <Link className={`fq-au-row is-${a.status.toLowerCase()}${urgent ? " is-urgent" : ""}`} href={`/auctions/detail?id=${a.auctionId}`} style={{ animationDelay: `${delay}ms` }}>
      <span className="fq-au-lot">{lot}</span>
      <Chip seed={a.auctionId} top="LOT" big={lot} size={64} still />
      <div className="fq-au-main">
        {a.status === "OPEN" ? <span className="fq-chip is-live">LIVE</span> : a.status === "SCHEDULED" ? <span className="fq-chip">예정</span> : <span className="fq-chip is-mute">종료</span>}
        <h3>{a.title}</h3>
        <small>{a.itemName}</small>
      </div>
      <div className="fq-au-price">
        <small>{a.status === "OPEN" ? "현재가" : "시작가"}</small>
        {a.status === "OPEN" && price !== undefined ? <Odometer value={price} className="fq-num" /> : <b className="fq-num">{won(a.startingPriceAmount)}</b>}
      </div>
      <div className="fq-au-time">
        <small>{a.status === "OPEN" ? "마감까지" : a.status === "SCHEDULED" ? "시작까지" : "종료"}</small>
        <b className="fq-num">{a.status === "OPEN" ? countdown(a.endsAt, now) : a.status === "SCHEDULED" ? countdown(a.startsAt, now) : dateTime(a.endsAt)}</b>
      </div>
      <span className="fq-au-go" aria-hidden="true">→</span>
    </Link>
  );
}
