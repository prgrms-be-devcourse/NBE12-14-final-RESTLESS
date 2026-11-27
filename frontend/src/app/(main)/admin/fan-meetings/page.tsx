"use client";

import LineArt from "@/components/LineArt";
import { useState } from "react";
import { FM_STATUS } from "@/components/Cards";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { FanMeetingDetail, FanMeetingPage } from "@/lib/types";
import AdminGate from "../admin-gate";
import { FanMeetingForm, type FmForm } from "../fanmeeting-form";
import { isoToLocal } from "../form-utils";

export default function AdminFanMeetingsPage() {
  return (
    <AdminGate lead={<>추첨 팬미팅을 만들고, 응모가 열리기 전(응모 예정)인 행사를 고치거나 취소해요. 본인이 만든 행사만 바뀌고, 관리자는 모든 행사를 다룰 수 있어요.</>}>
      <Manage />
    </AdminGate>
  );
}

/** 공개 상세에는 공지 시각과 공개 방식이 없어 고칠 때 다시 입력받는다 */
const toForm = (d: FanMeetingDetail): FmForm => ({
  title: d.title,
  entryOpensAt: isoToLocal(d.entryOpensAt),
  firstNoticeScheduledAt: "",
  meetingAt: isoToLocal(d.meetingAt),
  winnerLimit: d.winnerLimit,
  reserveLimit: d.reserveLimit,
  feeAmount: d.feeAmount,
  guerrilla: d.entryOpensAt === null,
});

function Manage() {
  const toast = useToast();
  const { profile } = useAuth();
  const list = useLoad(() => api<FanMeetingPage>("/api/fan-meetings?status=SCHEDULED&size=100").then((r) => r.items));
  const [editing, setEditing] = useState<FanMeetingDetail | null>(null);
  const [lookup, setLookup] = useState("");
  const [busy, setBusy] = useState<number | null>(null);

  const edit = async (eventId: number) => {
    setBusy(eventId);
    try {
      setEditing(await api<FanMeetingDetail>(`/api/fan-meetings/${eventId}`));
    } catch (err) {
      toast((err as ApiError).message, "err");
    } finally {
      setBusy(null);
    }
  };

  const cancel = async (eventId: number, title: string) => {
    if (!confirm(`‘${title}’ 팬미팅을 취소할까요? 되돌릴 수 없어요.`)) return;
    setBusy(eventId);
    try {
      await api(`/api/organizer/fan-meetings/${eventId}/cancel`, { method: "POST" });
      toast(`팬미팅 #${eventId}을 취소했어요`, "ok");
      if (editing?.eventId === eventId) setEditing(null);
      await list.reload().catch(() => undefined);
    } catch (err) {
      const ex = err as ApiError;
      toast(ex.code === "FAN_MEETING_NOT_EDITABLE" ? "응모가 이미 열렸거나 끝난 행사라 취소할 수 없어요." : ex.message, "err");
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <form
        className="fq-row"
        onSubmit={(e) => {
          e.preventDefault();
          void edit(Number(lookup));
        }}
      >
        <input className="fq-input" aria-label="팬미팅 ID" type="number" min={1} required value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="행사 ID로 찾기" />
        <button className="fq-btn is-ghost" disabled={busy !== null}>
          불러오기
        </button>
      </form>

      {editing ? (
        <div style={{ marginTop: 18 }}>
          <p className="fq-note">
            공지 시각과 공개 방식은 공개 정보에 없어서 다시 정해야 해요.
            {editing.entryOpensAt === null ? " 게릴라 오픈 행사라 응모 오픈 시각도 다시 입력해 주세요." : ""}
          </p>
          <FanMeetingForm
            key={editing.eventId}
            eventId={editing.eventId}
            initial={toForm(editing)}
            onSaved={(r) => {
              setEditing(r);
              void list.reload().catch(() => undefined);
            }}
          />
          <div className="fq-row" style={{ marginTop: 12 }}>
            <button type="button" className="fq-btn is-ghost is-sm" onClick={() => setEditing(null)}>
              새 팬미팅 만들기로
            </button>
            <button type="button" className="fq-btn is-danger is-sm" disabled={busy === editing.eventId} onClick={() => cancel(editing.eventId, editing.title)}>
              이 팬미팅 취소
            </button>
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 18 }}>
          <FanMeetingForm key="new" onCreated={() => void list.reload().catch(() => undefined)} />
        </div>
      )}

      <section className="fq-section" style={{ marginTop: 22 }}>
        <span className="fq-eyebrow">응모 예정 팬미팅</span>
        {list.error ? (
          <LoadError error={list.error} backHref="/admin" />
        ) : !list.data ? (
          <div className="fq-skel" style={{ height: 200 }} />
        ) : list.data.length === 0 ? (
          <div className="fq-card is-pad fq-empty">
            <LineArt kind="card" />
            <p>고칠 수 있는 팬미팅이 없어요.</p>
          </div>
        ) : (
          <ul className="fq-orders" style={{ marginTop: 12 }}>
            {list.data.map((m) => {
              const s = FM_STATUS[m.status] ?? { label: m.status, tone: "" };
              return (
                <li key={m.eventId} className="fq-card fq-order">
                  <div className="fq-order-b">
                    <div className="fq-row">
                      <span className={`fq-chip ${s.tone}`}>{s.label}</span>
                      {m.entryOpensAt === null ? <span className="fq-chip is-warn">게릴라</span> : null}
                    </div>
                    <h3>{m.title}</h3>
                    <small>
                      #{m.eventId}, {m.entryOpensAt ? `${dateTime(m.entryOpensAt)} 응모 오픈` : "오픈 시각 비공개"}, 팬미팅 {dateTime(m.meetingAt)}, 당첨 {m.winnerLimit}명, {m.feeAmount ? won(m.feeAmount) : "무료"}
                    </small>
                  </div>
                  <div className="fq-order-r fq-row">
                    <button type="button" className="fq-btn is-ghost is-sm" disabled={busy === m.eventId} onClick={() => edit(m.eventId)}>
                      고치기
                    </button>
                    <button type="button" className="fq-btn is-danger is-sm" disabled={busy === m.eventId} onClick={() => cancel(m.eventId, m.title)}>
                      취소
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
      {profile?.role === "ADMIN" ? <FanMeetingSummary /> : null}
    </>
  );
}

type FanMeetingSummaryData = {
  eventId: number; status: string; winnerLimit: number; reserveLimit: number; promotionClosesAt: string | null;
  applicationCount: number; occupied: number; selectionCounts: Record<string, number>; orderCounts: Record<string, number>;
  maxPromotedReserveRank: number | null; reviews: { orderId: number; reviewStartedAt: string; elapsedMinutes: number }[];
  refundPending: { orderId: number }[];
};

/** 관리자 전용 운영 현황. 응모가 열린 뒤의 행사도 ID로 조회한다 */
function FanMeetingSummary() {
  const [eventId, setEventId] = useState("");
  const [summary, setSummary] = useState<FanMeetingSummaryData | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(""); setSummary(null);
    try { setSummary(await api<FanMeetingSummaryData>(`/api/admin/fan-meetings/${Number(eventId)}/summary`)); }
    catch (e) { const err = e as ApiError; setError(err.message); }
    finally { setBusy(false); }
  };
  return <section className="fq-card is-pad fq-form" style={{ marginTop: 22 }}>
    <span className="fq-eyebrow">팬미팅 운영 현황 (관리자)</span>
    <form className="fq-row" onSubmit={load}>
      <input className="fq-input" aria-label="팬미팅 ID" type="number" min={1} required value={eventId} onChange={(e) => setEventId(e.target.value)} placeholder="행사 ID" />
      <button className="fq-btn is-ghost" disabled={busy}>{busy ? "조회 중…" : "현황 조회"}</button>
    </form>
    {error ? <p className="fq-err" role="alert">{error}</p> : null}
    {summary ? <><p>{summary.status}, 응모 {summary.applicationCount.toLocaleString()}명, 점유 {summary.occupied}/{summary.winnerLimit}</p>
      <p className="fq-muted">선정 현황: {Object.entries(summary.selectionCounts).map(([k,v]) => `${k} ${v}`).join(", ")}</p>
      <p className="fq-muted">주문 현황: {Object.entries(summary.orderCounts).map(([k,v]) => `${k} ${v}`).join(", ")}</p>
      {summary.maxPromotedReserveRank !== null ? <p>최대 승격 예비 순번: {summary.maxPromotedReserveRank}</p> : null}
      {summary.reviews.length ? <p>결제 확인 보류: {summary.reviews.map((r) => `#${r.orderId} (${r.elapsedMinutes}분)`).join(", ")}</p> : null}
      {summary.refundPending.length ? <p>환불 대기 주문: {summary.refundPending.map((r) => `#${r.orderId}`).join(", ")}</p> : null}
    </> : null}
  </section>;
}
