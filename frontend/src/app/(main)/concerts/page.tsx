"use client";

import DomainBox from "@/components/finale/DomainBox";
import { useState } from "react";
import { CardSkeletons, ConcertCard } from "@/components/Cards";
import Tabs from "@/components/Tabs";
import LoadError from "@/components/LoadError";
import { api } from "@/lib/api";
import { useLoad } from "@/lib/hooks";
import type { ConcertPage, PerformanceInfo } from "@/lib/types";
import ConcertShow from "./ConcertShow";
import { IntroGate } from "@/lib/intro";

/** 제목에서 크게 쓸 이름과 나머지를 나눈다. 앞의 [표시]는 뺀다 */
function splitTitle(title: string) {
  const clean = title.replace(/^\[[^\]]*\]\s*/, "");
  const [head, ...rest] = clean.split(/\s+/);
  return { head: head || title, rest: rest.join(" ") };
}

type F = "ALL" | "ON_SALE" | "SCHEDULED";

export default function ConcertsPage() {
  const [f, setF] = useState<F>("ALL");
  const { data, error } = useLoad(() => api<ConcertPage>("/api/concerts?size=40").then((r) => r.content));
  // 연출에 세울 대표 공연: 예매 중인 공연이 있으면 그 공연, 없으면 첫 공연
  const star = data?.find((c) => c.status === "ON_SALE") ?? data?.[0];
  const perfs = useLoad<PerformanceInfo[]>(() => (star ? api<PerformanceInfo[]>(`/api/concerts/${star.concertId}/performances`) : Promise.resolve([])), [star?.concertId]);
  const starList = [...(perfs.data ?? [])].sort((a, b) => +new Date(a.startsAt) - +new Date(b.startsAt));
  const starName = star ? splitTitle(star.title) : null;
  const list = (data ?? []).filter((c) => f === "ALL" || (f === "ON_SALE" ? c.status === "ON_SALE" : c.status !== "ON_SALE"));
  return (
    <>
      <IntroGate><ConcertShow seed={star?.concertId ?? 1} name={starName} onSale={star?.status === "ON_SALE"} list={starList} skipTo="#concert-list" /></IntroGate>
      <div id="concert-list" style={{ scrollMarginTop: 20 }} />
      <p className="fq-lead">좌석을 직접 골라 예매하는 지정석 공연이에요.</p>
      <Tabs
        label="공연 필터"
        value={f}
        onChange={setF}
        items={[
          { value: "ALL", label: "전체", count: data?.length },
          { value: "ON_SALE", label: "예매 중", count: data?.filter((c) => c.status === "ON_SALE").length },
          { value: "SCHEDULED", label: "예매 예정", count: data?.filter((c) => c.status !== "ON_SALE").length },
        ]}
      />
      <div className="fq-section" style={{ marginTop: 22 }}>
        {error ? <LoadError error={error} /> : data ? <div className="fq-grid">{list.map((c) => <ConcertCard key={c.concertId} c={c} />)}</div> : <CardSkeletons />}
      </div>
      <DomainBox domain="concert" />
    </>
  );
}
