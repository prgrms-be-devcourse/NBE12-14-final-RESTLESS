"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import LoadError from "@/components/LoadError";
import Tabs from "@/components/Tabs";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { FanMeetingForm } from "./fanmeeting-form";
import { iso, local } from "./form-utils";

type T = "drop" | "fanmeeting";
type FanMeetingSummary = {
  eventId: number; status: string; winnerLimit: number; reserveLimit: number; promotionClosesAt: string | null;
  applicationCount: number; occupied: number; selectionCounts: Record<string, number>; orderCounts: Record<string, number>;
  maxPromotedReserveRank: number | null; reviews: { orderId: number; reviewStartedAt: string; elapsedMinutes: number }[];
  refundPending: { orderId: number }[];
};


export default function AdminPage() {
  const { profile, ready } = useAuth();
  const router = useRouter();
  const [tab, setTab] = useState<T>("fanmeeting");
  const role = profile?.role;
  useEffect(() => {
    if (ready && !profile) router.replace(`/login?next=${encodeURIComponent("/admin")}`);
  }, [ready, profile, router]);
  if (!ready) return <div className="fq-skel" style={{ height: 300 }} />;
  if (!profile) return <div className="fq-skel" style={{ height: 300 }} />;
  if (profile.role === "USER") return <LoadError error={new ApiError(403, { code: "FORBIDDEN", message: "권한이 없어요." })} />;
  const items = profile.role === "ADMIN"
    ? [{ value: "drop" as const, label: "드롭 등록" }, { value: "fanmeeting" as const, label: "팬미팅 등록" }]
    : [{ value: "fanmeeting" as const, label: "팬미팅 등록" }];
  return (
    <>
      <p className="fq-lead">
        드롭과 팬미팅을 등록해요. 드롭은 <b>관리자</b>, 팬미팅은 <b>주최자</b> 권한이 필요해요.
        {role ? (
          <>
            {" "}
            지금 권한: <span className="fq-chip">{role}</span>
          </>
        ) : (
          " 로그인이 필요해요."
        )}
      </p>
      <nav className="fq-row" aria-label="주최 도구" style={{ margin: "14px 0 18px" }}>
        <Link className="fq-btn is-ghost is-sm" href="/admin/concerts">공연, 회차 관리</Link>
        <Link className="fq-btn is-ghost is-sm" href="/admin/fan-meetings">팬미팅 수정, 취소</Link>
        <Link className="fq-btn is-ghost is-sm" href="/admin/auctions">경매 등록, 관리</Link>
      </nav>
      <Tabs
        label="운영 메뉴"
        value={tab}
        onChange={setTab}
        items={items}
      />
      <div style={{ marginTop: 22 }}>
        {tab === "drop" && role === "ADMIN" ? <DropForm /> : tab === "fanmeeting" ? <><FanMeetingForm />{role === "ADMIN" ? <FanMeetingSummary /> : null}</> : null}
      </div>
    </>
  );
}

function DropForm() {
  const toast = useToast();
  const btn = useRef<HTMLButtonElement>(null);
  const [f, setF] = useState({
    concertId: 1,
    title: "",
    description: "",
    imageUrl: "https://",
    opensAt: local(60 * 60_000),
    closesAt: local(3 * 86400_000),
  });
  const [variants, setVariants] = useState([{ skuCode: "M", optionName: "M 사이즈", priceAmount: 69000, totalQuantity: 300 }]);
  const [created, setCreated] = useState<{ dropId: number; status?: string } | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const r = await api<{ dropId: number; status?: string }>("/api/admin/drops", { method: "POST", body: { ...f, opensAt: iso(f.opensAt), closesAt: iso(f.closesAt), variants } });
      setCreated(r);
      toast(`드롭 #${r.dropId} 초안을 만들었어요`, "ok");
    } catch (err) {
      toast((err as ApiError).message, "err");
    }
  };

  const publish = async () => {
    if (!created) return;
    try {
      const r = await api<{ dropId: number; status?: string }>(`/api/admin/drops/${created.dropId}/publish`, { method: "POST" });
      setCreated(r);
      burstFrom(btn.current);
      toast("드롭을 공개했어요", "ok");
    } catch (err) {
      toast((err as ApiError).message, "err");
    }
  };

  return (
    <form className="fq-card is-pad fq-form fq-admin" onSubmit={submit}>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="d-title">상품명</label>
          <input id="d-title" className="fq-input" required maxLength={200} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="NOVA 투어 후드 집업" />
        </div>
        <div className="fq-field">
          <label htmlFor="d-concert">연결 공연 ID</label>
          <input id="d-concert" className="fq-input" type="number" min={1} required value={f.concertId} onChange={(e) => setF({ ...f, concertId: Number(e.target.value) })} />
        </div>
      </div>
      <div className="fq-field">
        <label htmlFor="d-desc">설명</label>
        <textarea id="d-desc" className="fq-input" required value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
      </div>
      <div className="fq-field">
        <label htmlFor="d-img">이미지 URL</label>
        <input id="d-img" className="fq-input" required maxLength={500} value={f.imageUrl} onChange={(e) => setF({ ...f, imageUrl: e.target.value })} />
      </div>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="d-open">판매 시작</label>
          <input id="d-open" className="fq-input" type="datetime-local" required value={f.opensAt} onChange={(e) => setF({ ...f, opensAt: e.target.value })} />
        </div>
        <div className="fq-field">
          <label htmlFor="d-close">판매 종료</label>
          <input id="d-close" className="fq-input" type="datetime-local" required value={f.closesAt} onChange={(e) => setF({ ...f, closesAt: e.target.value })} />
        </div>
      </div>

      <div className="fq-between">
        <span className="fq-eyebrow">옵션, 재고</span>
        <button type="button" className="fq-btn is-ghost is-sm" onClick={() => setVariants([...variants, { skuCode: "", optionName: "", priceAmount: 69000, totalQuantity: 100 }])}>
          + 옵션 추가
        </button>
      </div>
      {variants.map((v, i) => (
        <div key={i} className="fq-variant-row">
          <input className="fq-input" aria-label="SKU" placeholder="SKU" required value={v.skuCode} onChange={(e) => setVariants(variants.map((x, j) => (j === i ? { ...x, skuCode: e.target.value } : x)))} />
          <input className="fq-input" aria-label="옵션명" placeholder="옵션명" required value={v.optionName} onChange={(e) => setVariants(variants.map((x, j) => (j === i ? { ...x, optionName: e.target.value } : x)))} />
          <input className="fq-input" aria-label="가격" type="number" min={1} required value={v.priceAmount} onChange={(e) => setVariants(variants.map((x, j) => (j === i ? { ...x, priceAmount: Number(e.target.value) } : x)))} />
          <input className="fq-input" aria-label="수량" type="number" min={1} required value={v.totalQuantity} onChange={(e) => setVariants(variants.map((x, j) => (j === i ? { ...x, totalQuantity: Number(e.target.value) } : x)))} />
          <button type="button" className="fq-btn is-ghost is-sm" aria-label="옵션 삭제" disabled={variants.length === 1} onClick={() => setVariants(variants.filter((_, j) => j !== i))}>
            ✕
          </button>
        </div>
      ))}

      <div className="fq-row">
        <button className="fq-btn is-grad">초안 만들기</button>
        {created ? (
          <>
            <button type="button" ref={btn} className="fq-btn is-hot" onClick={publish} disabled={created.status === "SCHEDULED" || created.status === "OPEN"}>
              공개하기
            </button>
            <span className="fq-chip is-mint">
              #{created.dropId}, {created.status ?? "DRAFT"}
            </span>
          </>
        ) : null}
      </div>
    </form>
  );
}

function FanMeetingSummary() {
  const [eventId, setEventId] = useState("");
  const [summary, setSummary] = useState<FanMeetingSummary | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(""); setSummary(null);
    try { setSummary(await api<FanMeetingSummary>(`/api/admin/fan-meetings/${Number(eventId)}/summary`)); }
    catch (e) { const err = e as ApiError; setError(err.message); }
    finally { setBusy(false); }
  };
  return <section className="fq-card is-pad fq-form" style={{ marginTop: 18 }}>
    <span className="fq-eyebrow">팬미팅 운영 현황</span>
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
