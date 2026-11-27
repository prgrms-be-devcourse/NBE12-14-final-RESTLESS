"use client";

import { useMemo } from "react";
import { firstWord } from "@/components/Cards";
import Orbit, { type OrbitCard } from "@/components/Orbit";
import { api } from "@/lib/api";
import { useLoad } from "@/lib/hooks";
import type { AuctionPage, ConcertPage, DropPage } from "@/lib/types";

const ON_SALE = ["ON_SALE", "OPEN"];

export default function HomePage() {
  const concerts = useLoad(() => api<ConcertPage>("/api/concerts?size=8").then((r) => r.content));
  const drops = useLoad(() => api<DropPage>("/api/drops").then((r) => r.items));
  const auctions = useLoad(() => api<AuctionPage>("/api/auctions?size=8").then((r) => r.items));

  const live = auctions.data?.filter((a) => a.status === "OPEN") ?? [];
  const onSale = (drops.data ?? []).filter((d) => ON_SALE.includes(d.saleStatus));
  const openConcerts = (concerts.data ?? []).filter((c) => c.status === "ON_SALE");

  const orbit = useMemo<OrbitCard[]>(() => {
    const loaded = (v: unknown, n: number) => (v ? n : null);
    const firstConcert = openConcerts[0] ?? concerts.data?.[0];
    return [
      {
        key: "concert",
        listHref: "/concerts",
        world: "#3d8bff",
        eyebrow: "Live Concert",
        title: "공연 예매",
        label: "좌석을 직접 골라 예매해요. 선택한 좌석은 결제 기한까지 유지돼요.",
        count: loaded(concerts.data, openConcerts.length || (concerts.data?.length ?? 0)),
        countLabel: openConcerts.length ? "예매 중" : "예매 예정",
        headline: firstConcert?.title ?? "예정된 공연이 없어요",
        href: firstConcert ? `/concerts/detail?id=${firstConcert.concertId}` : "/concerts",
        seed: firstConcert?.concertId ?? 1,
        word: firstConcert ? firstWord(firstConcert.title) : "LIVE",
      },
      {
        key: "drop",
        listHref: "/drops",
        world: "#ff7a45",
        eyebrow: "Limited Drop",
        title: "한정판 드롭",
        label: "수량이 정해진 한정판 굿즈를 대기열 순서대로 구매해요.",
        count: loaded(drops.data, onSale.length),
        countLabel: "판매 중",
        headline: onSale[0]?.title ?? "판매 중인 드롭이 없어요",
        href: onSale[0] ? `/drops/detail?id=${onSale[0].dropId}` : "/drops",
        seed: (onSale[0]?.dropId ?? 0) + 1,
        word: onSale[0] ? firstWord(onSale[0].title) : "DROP",
      },
      {
        key: "fanmeeting",
        listHref: "/fan-meetings",
        world: "#ff7a93",
        eyebrow: "Fan Meeting",
        title: "팬미팅 추첨",
        label: "응모 후 추첨으로 참가자를 정해요. 당첨되면 결제 기한 안에 결제해요.",
        count: null,
        countLabel: "응모 일정",
        headline: "일정 미정",
        href: "/fan-meetings",
        seed: 4,
        word: "MEET",
      },
      {
        key: "auction",
        listHref: "/auctions",
        world: "#e3263a",
        eyebrow: "Live Auction",
        title: "라이브 경매",
        label: "공연 관련 물품을 실시간 입찰로 판매해요. 마감 시점의 최고가 입찰자가 낙찰받아요.",
        count: loaded(auctions.data, live.length),
        countLabel: "진행 중",
        headline: live[0]?.title ?? "진행 중인 경매가 없어요",
        href: live[0] ? `/auctions/detail?id=${live[0].auctionId}` : "/auctions",
        seed: (live[0]?.auctionId ?? 0) + 3,
        word: live[0]?.itemName ?? "LOT",
      },
    ];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [concerts.data, drops.data, auctions.data]);

  return (
    <div className="fq-home">
      <Orbit cards={orbit} />
    </div>
  );
}
