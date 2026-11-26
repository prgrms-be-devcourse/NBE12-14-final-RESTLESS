"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useRef, useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { AuctionDetail, AuctionPage } from "@/lib/types";
import AdminGate from "../admin-gate";
import { firstFieldError, iso, isoToLocal, local } from "../form-utils";

export default function AdminAuctionsPage() {
  return (
    <AdminGate lead={<>경매를 등록하고, 시작 전인 경매를 고치거나 지워요. 본인이 등록한 경매만 바뀌고, 관리자는 모든 경매를 다룰 수 있어요.</>}>
      <Manage />
    </AdminGate>
  );
}

type AuForm = {
  title: string;
  itemName: string;
  itemDescription: string;
  startingPriceAmount: number;
  minimumIncrementAmount: number;
  startsAt: string;
  endsAt: string;
  extensionDurationSeconds: number;
};

// 백엔드 Auction.MIN/MAX/DEFAULT_EXTENSION_SECONDS
const EXT_MIN = 30;
const EXT_MAX = 300;

const sample = (): AuForm => ({
  title: "NOVA 월드투어 친필 사인 포스터",
  itemName: "친필 사인 포스터 (A2)",
  itemDescription: "서울 공연 리허설 날 멤버 전원이 사인한 포스터예요.",
  startingPriceAmount: 50000,
  minimumIncrementAmount: 1000,
  startsAt: local(60 * 60_000),
  endsAt: local(2 * 60 * 60_000),
  extensionDurationSeconds: 60,
});

const fromDetail = (d: AuctionDetail): AuForm => ({
  title: d.title,
  itemName: d.itemName,
  itemDescription: d.itemDescription,
  startingPriceAmount: d.startingPriceAmount,
  minimumIncrementAmount: d.minimumIncrementAmount,
  startsAt: isoToLocal(d.startsAt),
  endsAt: isoToLocal(d.endsAt),
  extensionDurationSeconds: d.extensionDurationSeconds,
});

/** 백엔드와 같은 규칙: 시작은 지금 이후, 시작 < 종료, 금액 1원 이상, 연장 30~300초 */
function check(f: AuForm): string | null {
  if (!f.title.trim() || !f.itemName.trim() || !f.itemDescription.trim()) return "제목, 상품명, 설명을 모두 입력해 주세요.";
  if (f.startingPriceAmount < 1 || f.minimumIncrementAmount < 1) return "시작가와 최소 입찰 단위는 1원 이상이어야 해요.";
  if (!Number.isInteger(f.extensionDurationSeconds) || f.extensionDurationSeconds < EXT_MIN || f.extensionDurationSeconds > EXT_MAX)
    return `마감 연장 시간은 ${EXT_MIN}초에서 ${EXT_MAX}초 사이여야 해요.`;
  const s = Date.parse(f.startsAt);
  const e = Date.parse(f.endsAt);
  if (Number.isNaN(s) || Number.isNaN(e)) return "시작, 종료 시각을 정해 주세요.";
  if (s <= Date.now()) return "시작 시각은 지금 이후여야 해요.";
  if (e <= s) return "종료 시각은 시작 시각보다 뒤여야 해요.";
  return null;
}

function Manage() {
  const toast = useToast();
  const { profile } = useAuth();
  const list = useLoad(() => api<AuctionPage>("/api/auctions?status=SCHEDULED&size=100").then((r) => r.items));
  const [editing, setEditing] = useState<AuctionDetail | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const open = async (auctionId: number) => {
    setBusy(auctionId);
    try {
      setEditing(await api<AuctionDetail>(`/api/auctions/${auctionId}`));
    } catch (err) {
      toast((err as ApiError).message, "err");
    } finally {
      setBusy(null);
    }
  };

  const remove = async (auctionId: number, title: string) => {
    if (!confirm(`‘${title}’ 경매를 삭제할까요? 되돌릴 수 없어요.`)) return;
    setBusy(auctionId);
    try {
      await api(`/api/auctions/${auctionId}`, { method: "DELETE" });
      toast(`경매 #${auctionId}을 삭제했어요`, "ok");
      if (editing?.auctionId === auctionId) setEditing(null);
      await list.reload().catch(() => undefined);
    } catch (err) {
      const ex = err as ApiError;
      toast(ex.code === "AUCTION_ALREADY_STARTED" ? "이미 시작한 경매는 삭제할 수 없어요." : ex.message, "err");
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      {editing ? (
        <>
          <AuctionForm
            key={editing.auctionId}
            auctionId={editing.auctionId}
            initial={fromDetail(editing)}
            onSaved={(d) => {
              setEditing(d);
              void list.reload().catch(() => undefined);
            }}
          />
          <div className="fq-row" style={{ marginTop: 12 }}>
            <button type="button" className="fq-btn is-ghost is-sm" onClick={() => setEditing(null)}>
              새 경매 등록으로
            </button>
            <button type="button" className="fq-btn is-danger is-sm" disabled={busy === editing.auctionId} onClick={() => remove(editing.auctionId, editing.title)}>
              이 경매 삭제
            </button>
          </div>
        </>
      ) : (
        <AuctionForm key="new" onCreated={() => void list.reload().catch(() => undefined)} />
      )}

      <section className="fq-section" style={{ marginTop: 22 }}>
        <span className="fq-eyebrow">시작 전 경매</span>
        {list.error ? (
          <LoadError error={list.error} backHref="/admin" />
        ) : !list.data ? (
          <div className="fq-skel" style={{ height: 200 }} />
        ) : list.data.length === 0 ? (
          <div className="fq-card is-pad fq-empty">
            <LineArt kind="chip" />
            <p>시작 전인 경매가 없어요.</p>
          </div>
        ) : (
          <ul className="fq-orders" style={{ marginTop: 12 }}>
            {list.data.map((a) => (
              <li key={a.auctionId} className="fq-card fq-order">
                <div className="fq-order-b">
                  <div className="fq-row">
                    <span className="fq-chip">예정</span>
                    {editing?.auctionId === a.auctionId ? <span className="fq-chip is-mint">고치는 중</span> : null}
                  </div>
                  <h3>{a.title}</h3>
                  <small>
                    #{a.auctionId}, {a.itemName}, 시작가 {won(a.startingPriceAmount)}, {dateTime(a.startsAt)} ~ {dateTime(a.endsAt)}
                  </small>
                </div>
                <div className="fq-order-r fq-row">
                  <Link className="fq-btn is-ghost is-sm" href={`/auctions/${a.auctionId}`}>
                    보기
                  </Link>
                  <button type="button" className="fq-btn is-ghost is-sm" disabled={busy === a.auctionId} onClick={() => open(a.auctionId)}>
                    고치기
                  </button>
                  <button type="button" className="fq-btn is-danger is-sm" disabled={busy === a.auctionId} onClick={() => remove(a.auctionId, a.title)}>
                    삭제
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {profile?.role === "ORGANIZER" ? <p className="fq-muted" style={{ marginTop: 10 }}>다른 주최자의 경매는 고치거나 지우면 찾을 수 없다는 응답을 받아요.</p> : null}
      </section>
    </>
  );
}

function AuctionForm({
  auctionId,
  initial,
  onSaved,
  onCreated,
}: {
  auctionId?: number;
  initial?: AuForm;
  onSaved?: (d: AuctionDetail) => void;
  onCreated?: (id: number) => void;
}) {
  const toast = useToast();
  const btn = useRef<HTMLButtonElement>(null);
  const editing = auctionId !== undefined;
  const [f, setF] = useState<AuForm>(() => initial ?? sample());
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<number | null>(null);
  const set = <K extends keyof AuForm>(k: K, v: AuForm[K]) => setF({ ...f, [k]: v });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const msg = check(f);
    setError(msg);
    if (msg) return;
    // 수정(PATCH)도 모든 항목을 다시 보낸다
    const body = { ...f, title: f.title.trim(), itemName: f.itemName.trim(), startsAt: iso(f.startsAt), endsAt: iso(f.endsAt) };
    setSaving(true);
    try {
      if (editing) {
        const d = await api<AuctionDetail>(`/api/auctions/${auctionId}`, { method: "PATCH", body });
        onSaved?.(d);
        toast(`경매 #${auctionId}을 고쳤어요`, "ok");
      } else {
        const r = await api<{ auctionId: number }>("/api/auctions", { method: "POST", body });
        setCreated(r.auctionId);
        burstFrom(btn.current);
        toast(`경매 #${r.auctionId}을 등록했어요`, "ok");
        onCreated?.(r.auctionId);
      }
    } catch (err) {
      const ex = err as ApiError;
      const m = ex.code === "AUCTION_ALREADY_STARTED" ? "이미 시작한 경매는 고칠 수 없어요." : firstFieldError(ex.details) ?? ex.message;
      setError(m);
      toast(m, "err");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="fq-card is-pad fq-form fq-admin" onSubmit={submit} noValidate>
      <div className="fq-between">
        <span className="fq-eyebrow">{editing ? `경매 #${auctionId} 고치기` : "경매 등록"}</span>
        {editing ? null : (
          <button type="button" className="fq-btn is-ghost is-sm" onClick={() => setF(sample())}>
            예시 값 채우기
          </button>
        )}
      </div>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="a-title">경매 제목</label>
          <input id="a-title" className="fq-input" maxLength={200} value={f.title} onChange={(e) => set("title", e.target.value)} />
        </div>
        <div className="fq-field">
          <label htmlFor="a-item">상품명</label>
          <input id="a-item" className="fq-input" maxLength={200} value={f.itemName} onChange={(e) => set("itemName", e.target.value)} />
        </div>
      </div>
      <div className="fq-field">
        <label htmlFor="a-desc">상품 설명</label>
        <textarea id="a-desc" className="fq-input" maxLength={5000} value={f.itemDescription} onChange={(e) => set("itemDescription", e.target.value)} />
      </div>
      <div className="fq-form-2" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))" }}>
        <div className="fq-field">
          <label htmlFor="a-start-price">시작가 ({won(f.startingPriceAmount || 0)})</label>
          <input id="a-start-price" className="fq-input" type="number" min={1} step={1} value={f.startingPriceAmount} onChange={(e) => set("startingPriceAmount", Number(e.target.value))} />
        </div>
        <div className="fq-field">
          <label htmlFor="a-inc">최소 입찰 단위</label>
          <input id="a-inc" className="fq-input" type="number" min={1} step={1} value={f.minimumIncrementAmount} onChange={(e) => set("minimumIncrementAmount", Number(e.target.value))} />
        </div>
        <div className="fq-field">
          <label htmlFor="a-ext">마감 연장 (초)</label>
          <input id="a-ext" className="fq-input" type="number" min={EXT_MIN} max={EXT_MAX} value={f.extensionDurationSeconds} onChange={(e) => set("extensionDurationSeconds", Number(e.target.value))} />
        </div>
      </div>
      <p className="fq-hint">마감 직전에 입찰이 들어오면 마감 시각을 이만큼 늦춰요.</p>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="a-starts">시작 시각</label>
          <input id="a-starts" className="fq-input" type="datetime-local" value={f.startsAt} onChange={(e) => set("startsAt", e.target.value)} />
        </div>
        <div className="fq-field">
          <label htmlFor="a-ends">종료 시각</label>
          <input id="a-ends" className="fq-input" type="datetime-local" value={f.endsAt} onChange={(e) => set("endsAt", e.target.value)} />
        </div>
      </div>
      {error ? (
        <p className="fq-err" role="alert">
          {error}
        </p>
      ) : null}
      <div className="fq-row">
        <button ref={btn} className="fq-btn is-grad" disabled={saving}>
          {saving ? "저장 중…" : editing ? "고친 내용 저장" : "경매 등록"}
        </button>
        {created ? (
          <Link className="fq-chip is-mint" href={`/auctions/${created}`}>
            #{created}, 예정
          </Link>
        ) : null}
      </div>
    </form>
  );
}
