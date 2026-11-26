"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { countdown, dateTime, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import { checkoutHref } from "@/lib/orders";
import type { FanMeetingDetail, MyFanMeetingOrder, MyFanMeetingResult } from "@/lib/types";

export default function ResultView({ id }: { id: number }) {
  const now = useNow();
  const toast = useToast();
  const { profile, ready } = useAuth();
  const { data: f, error, loading } = useLoad(() => api<FanMeetingDetail>(`/api/fan-meetings/${id}`), [id]);
  const [real, setReal] = useState<MyFanMeetingResult | null | undefined>(undefined);
  const [order, setOrder] = useState<MyFanMeetingOrder | null>(null);
  const [busy, setBusy] = useState(false);
  const celebrated = useRef(false);
  const heroRef = useRef<HTMLDivElement>(null);

  const loadResult = async () => {
    const result = await api<MyFanMeetingResult>(`/api/fan-meetings/${id}/result/me`);
    setReal(result);
    if (result.orderId) {
      try { setOrder(await api<MyFanMeetingOrder>(`/api/fan-meetings/${id}/orders/me`)); }
      catch { setOrder(null); }
    } else setOrder(null);
  };

  useEffect(() => {
    if (!profile) return;
    loadResult().catch((e) => setReal(null));
  }, [id, profile?.memberId, f?.feeAmount]);

  const mine = real;
  const viewOrder = order;

  useEffect(() => {
    if (mine?.result === "WINNER" && !celebrated.current) {
      celebrated.current = true;
      setTimeout(() => burstFrom(heroRef.current), 250);
    }
  }, [mine?.result]);

  const decline = async () => {
    if (!order?.declinable || !order.orderId) return;
    if (!confirm("결제를 포기하면 다음 예비 순번에게 자리가 넘어가요. 포기할까요?")) return;
    setBusy(true);
    try {
      await api(`/api/fan-meetings/${id}/orders/${order.orderId}/decline`, { method: "POST" });
      toast("결제를 포기했어요. 자리는 다음 예비 순번에게 넘어가요");
      await loadResult();
    } catch (e) { toast((e as ApiError).message, "err"); }
    finally { setBusy(false); }
  };

  const confirmFree = async () => {
    if (!order?.orderId) return;
    setBusy(true);
    try {
      await api(`/api/fan-meetings/${id}/orders/${order.orderId}/confirm`, { method: "POST" });
      await loadResult();
      toast("무료 참가를 확정했어요", "ok");
    } catch (e) { toast((e as ApiError).message, "err"); }
    finally { setBusy(false); }
  };

  if (ready && !profile) return <div className="fq-card is-pad fq-empty"><div className="fq-done-ico">Q</div><h2>로그인하고 추첨 결과를 확인하세요</h2><Link className="fq-btn is-grad" href={`/login?next=${encodeURIComponent(`/fan-meetings/${id}/result`)}`}>로그인</Link></div>;

  const status = mine?.result;
  const deadline = mine?.paymentDeadline ?? viewOrder?.paymentDeadline ?? null;
  const expired = deadline ? msLeft(deadline, now) <= 0 : false;
  const fee = viewOrder?.amount ?? f?.feeAmount ?? 0;
  const hasPayableOrder = !!mine?.orderId && !!viewOrder && viewOrder.status === "PENDING_PAYMENT";

  if (error) return <LoadError error={error} backHref="/fan-meetings" />;
  if (loading || !f) return <div className="fq-skel" style={{ height: 360 }} />;

  return (
    <div className="fq-detail">
      <Link href={`/fan-meetings/${id}`} className="fq-back">← 팬미팅 상세</Link>
      {real === undefined || !f ? <div className="fq-skel" style={{ height: 360, maxWidth: 620, margin: "0 auto" }} />
      : !mine || status === "NOT_APPLIED" ? <div className="fq-card is-pad fq-result"><span className="fq-eyebrow">MY RESULT</span><h2>이 팬미팅에 응모한 기록이 없어요</h2><p className="fq-muted">{f.title}</p><Link className="fq-btn is-ghost" href="/fan-meetings">다른 팬미팅 보러 가기</Link></div>
      : status === "WINNER" || (status === "RESERVE" && mine.orderId) ? <div ref={heroRef} className="fq-card is-pad fq-result is-win">
          <div><span className="fq-chip is-live">{status === "WINNER" ? "당첨, 결제 대기" : "예비 순번 승격, 결제 대기"}</span></div>
          <h2>{status === "WINNER" ? "팬미팅에 당첨됐어요" : "예비 순번에서 당첨으로 바뀌었어요"}</h2>
          <p className="fq-muted">{f.title}, {dateTime(f.meetingAt)}<br />결제 기한 안에 결제해야 참여가 확정돼요.</p>
          <div className="fq-clock"><span className="fq-muted" style={{ fontSize: 13 }}>결제 기한까지 남은 시간</span><b className="fq-num">{deadline ? countdown(deadline, now) : "--:--"}</b><span className="fq-muted fq-num" style={{ fontSize: 13 }}>참가비 {won(fee)}</span></div>
          {expired ? <p className="fq-err">결제 기한이 지났어요.</p> : hasPayableOrder && fee === 0 ? <button className="fq-btn is-hot is-block fq-big" disabled={busy} onClick={confirmFree}>무료 참가 확정</button> : hasPayableOrder ? <>
            <Link className="fq-btn is-hot is-block fq-big" href={checkoutHref({ orderId: viewOrder!.orderId, kind: "FAN_MEETING", title: f.title, amount: fee, paymentExpiresAt: deadline, back: `/fan-meetings/${id}/result`, eventId: id })}>{won(fee)} 결제하기</Link>
            {viewOrder?.declinable ? <button className="fq-btn is-ghost is-block" disabled={busy} onClick={decline}>결제 포기</button> : null}
          </> : <p className="fq-muted">주문 상태를 확인하고 있어요.</p>}
        </div>
      : status === "RESERVE" ? <div className="fq-card is-pad fq-result"><div><span className="fq-chip is-warn">예비, 대기 중</span></div><h2>예비 순번 <span className="fq-rank">{mine.reserveRank ?? "-"}번</span>이에요</h2><p className="fq-muted" style={{ maxWidth: 440, margin: "0 auto", lineHeight: 1.7 }}>당첨자 중 결제하지 않거나 포기한 분이 생기면 순번대로 승격돼요. 승격되면 결제 기한이 주어져요.</p><Link className="fq-btn is-ghost" href="/me/orders">내 참여 내역 보기</Link></div>
      : status === "NOT_SELECTED" ? <div className="fq-card is-pad fq-result"><div><span className="fq-chip is-mute">미당첨</span></div><h2>이번 추첨에는 당첨되지 않았어요</h2><p className="fq-muted">다른 팬미팅과 드롭도 확인해 보세요.</p><div className="fq-row" style={{ justifyContent: "center" }}><Link className="fq-btn is-grad" href="/fan-meetings">다른 팬미팅 보기</Link><Link className="fq-btn is-ghost" href="/home">홈으로</Link></div></div>
      : <div className="fq-card is-pad fq-result"><div><span className="fq-chip">응모 완료</span></div><h2>추첨을 기다리고 있어요</h2><p className="fq-muted">{f.title}, 추첨이 끝나면 여기에서 결과를 확인할 수 있어요.</p></div>}
    </div>
  );
}
