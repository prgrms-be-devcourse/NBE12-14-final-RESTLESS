"use client";

import LineArt from "@/components/LineArt";
import { useState } from "react";
import { CardSkeletons, FanMeetingCard } from "@/components/Cards";
import DomainBox from "@/components/finale/DomainBox";
import LoadError from "@/components/LoadError";
import Tabs from "@/components/Tabs";
import { api } from "@/lib/api";
import { useLoad } from "@/lib/hooks";
import type { FanMeetingPage, FanMeetingStatus } from "@/lib/types";
import LotteryShow from "@/components/lottery/LotteryShow";
import { IntroGate } from "@/lib/intro";

type F = "ALL" | "OPEN" | "SCHEDULED" | "ENDED";
const ENDED: FanMeetingStatus[] = ["CLOSED", "DRAWING", "PAYMENT_IN_PROGRESS", "RECRUITMENT_COMPLETED"];
// 응모 중, 예정, 마감 순으로 보여 주고 취소된 행사는 목록에서 뺀다
const ORDER: Partial<Record<FanMeetingStatus, number>> = { OPEN: 0, SCHEDULED: 1 };

export default function FanMeetingsPage() {
  const [f, setF] = useState<F>("ALL");
  const { data, error } = useLoad(() => api<FanMeetingPage>("/api/fan-meetings?size=100").then((r) => r.items.filter((m) => m.status !== "CANCELLED")));
  const all = [...(data ?? [])].sort((a, b) => (ORDER[a.status] ?? 2) - (ORDER[b.status] ?? 2) || +new Date(a.meetingAt) - +new Date(b.meetingAt));
  const match = (x: F, s: FanMeetingStatus) => x === "ALL" || (x === "ENDED" ? ENDED.includes(s) : s === x);
  const list = all.filter((m) => match(f, m.status));
  const count = (x: F) => (data ? all.filter((m) => match(x, m.status)).length : undefined);
  return (
    <>
      <IntroGate><LotteryShow skipTo="#fm-list" /></IntroGate>
      <div id="fm-list" style={{ scrollMarginTop: 20, marginTop: 28 }} />
      <p className="fq-lead">응모한 팬 중에서 추첨으로 참가자를 정해요. 당첨되면 결제 기한 안에 결제해 주세요.</p>
      <Tabs
        label="팬미팅 필터"
        value={f}
        onChange={setF}
        items={[
          { value: "ALL", label: "전체", count: count("ALL") },
          { value: "OPEN", label: "응모 중", count: count("OPEN") },
          { value: "SCHEDULED", label: "응모 예정", count: count("SCHEDULED") },
          { value: "ENDED", label: "응모 마감", count: count("ENDED") },
        ]}
      />
      <div className="fq-section" style={{ marginTop: 22 }}>
        {error ? (
          <LoadError error={error} />
        ) : !data ? (
          <CardSkeletons />
        ) : list.length === 0 ? (
          <div className="fq-card is-pad fq-empty"><LineArt kind="card" /><p>해당하는 팬미팅이 없어요.</p></div>
        ) : (
          <div className="fq-grid">{list.map((m) => <FanMeetingCard key={m.eventId} f={m} />)}</div>
        )}
      </div>
      <DomainBox domain="fanmeeting" />
    </>
  );
}
