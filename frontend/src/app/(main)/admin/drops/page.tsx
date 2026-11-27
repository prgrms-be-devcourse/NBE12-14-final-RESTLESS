"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useRef, useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { dateTime } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { DropManaged, DropManagedPage } from "@/lib/types";
import AdminGate from "../admin-gate";
import { firstFieldError, iso, local, toLocal } from "../form-utils";

export default function AdminDropsPage() {
  return (
    <AdminGate lead={<>드롭을 초안으로 만들고 공개해요. 판매 중이어도 삭제할 수 있고, 결제된 주문은 환불 배치가 처리해요. 주최자는 본인 공연의 드롭만, 관리자는 모든 드롭을 다뤄요.</>}>
      <Manage />
    </AdminGate>
  );
}

type Admin = { dropId: number; status: string };

/** 주최자 오픈 시각은 KST 08~22시 정각만 받는다(백엔드 #438). 다음 정각을 그 범위로 맞춘다 */
const nextOrganizerHour = () => {
  const d = new Date(Date.now() + 60 * 60_000);
  d.setMinutes(0, 0, 0);
  if (d.getHours() < 8) d.setHours(8);
  if (d.getHours() > 22) {
    d.setDate(d.getDate() + 1);
    d.setHours(8);
  }
  return toLocal(d.getTime());
};

/** 상태는 게시 여부만 담는다. 판매 중인지는 판매 기간으로 판단한다 */
const stage = (d: DropManaged) => {
  if (d.status === "DRAFT") return { label: "초안", tone: "is-mute" };
  if (d.status === "CANCELLED") return { label: "삭제됨", tone: "is-warn" };
  const now = Date.now();
  if (now < Date.parse(d.opensAt)) return { label: "판매 예정", tone: "is-mint" };
  if (now < Date.parse(d.closesAt) && d.status !== "CLOSED") return { label: "판매 중", tone: "is-live" };
  return { label: "판매 종료", tone: "is-mute" };
};

function Manage() {
  const toast = useToast();
  const list = useLoad(() => api<DropManagedPage>("/api/organizer/drops").then((r) => r.items));
  const [busy, setBusy] = useState<number | null>(null);
  const reload = () => void list.reload().catch(() => undefined);

  const publish = async (d: DropManaged, el: HTMLElement) => {
    setBusy(d.dropId);
    try {
      await api<Admin>(`/api/organizer/drops/${d.dropId}/publish`, { method: "POST" });
      burstFrom(el);
      toast(`드롭 #${d.dropId}을 공개했어요`, "ok");
      reload();
    } catch (err) {
      toast((err as ApiError).message, "err");
    } finally {
      setBusy(null);
    }
  };

  const remove = async (d: DropManaged) => {
    const onSale = stage(d).label === "판매 중";
    if (!confirm(`‘${d.title}’ 드롭을 삭제할까요?${onSale ? " 판매 중이라 새 구매가 바로 막히고, 결제된 주문은 환불돼요." : ""} 되돌릴 수 없어요.`)) return;
    setBusy(d.dropId);
    try {
      await api<Admin>(`/api/organizer/drops/${d.dropId}`, { method: "DELETE" });
      toast(`드롭 #${d.dropId}을 삭제했어요`, "ok");
      reload();
    } catch (err) {
      toast((err as ApiError).message, "err");
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <DropForm onCreated={reload} />
      <section className="fq-section" style={{ marginTop: 22 }}>
        <span className="fq-eyebrow">내 드롭</span>
        {list.error ? (
          <LoadError error={list.error} backHref="/admin" />
        ) : !list.data ? (
          <div className="fq-skel" style={{ height: 200 }} />
        ) : list.data.length === 0 ? (
          <div className="fq-card is-pad fq-empty">
            <LineArt kind="box" />
            <p>아직 만든 드롭이 없어요.</p>
          </div>
        ) : (
          <ul className="fq-orders" style={{ marginTop: 12 }}>
            {list.data.map((d) => {
              const s = stage(d);
              return (
                <li key={d.dropId} className="fq-card fq-order">
                  <div className="fq-order-b">
                    <div className="fq-row">
                      <span className={`fq-chip ${s.tone}`}>{s.label}</span>
                    </div>
                    <h3>{d.title}</h3>
                    <small>
                      #{d.dropId}, 공연 #{d.concertId}, 판매 {dateTime(d.opensAt)} ~ {dateTime(d.closesAt)}
                    </small>
                  </div>
                  <div className="fq-order-r fq-row">
                    {d.status === "DRAFT" ? (
                      <button type="button" className="fq-btn is-hot is-sm" disabled={busy === d.dropId} onClick={(e) => publish(d, e.currentTarget)}>
                        공개
                      </button>
                    ) : d.status !== "CANCELLED" ? (
                      <Link className="fq-btn is-ghost is-sm" href={`/drops/detail?id=${d.dropId}`}>
                        공개 화면
                      </Link>
                    ) : null}
                    {d.status !== "CANCELLED" ? (
                      <button type="button" className="fq-btn is-danger is-sm" disabled={busy === d.dropId} onClick={() => remove(d)}>
                        삭제
                      </button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </>
  );
}

function DropForm({ onCreated }: { onCreated: () => void }) {
  const toast = useToast();
  const { profile } = useAuth();
  const isAdmin = profile?.role === "ADMIN";
  const btn = useRef<HTMLButtonElement>(null);
  const [f, setF] = useState(() => ({
    concertId: 1,
    title: "",
    description: "",
    imageUrl: "https://",
    opensAt: isAdmin ? local(60 * 60_000) : nextOrganizerHour(),
    closesAt: local(3 * 86400_000),
  }));
  const [variants, setVariants] = useState([{ skuCode: "M", optionName: "M 사이즈", priceAmount: 69000, totalQuantity: 300 }]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      // 관리자 등록은 오픈 시각 제한이 없는 관리자 API를 쓴다
      const r = await api<Admin>(isAdmin ? "/api/admin/drops" : "/api/organizer/drops", {
        method: "POST",
        body: { ...f, opensAt: iso(f.opensAt), closesAt: iso(f.closesAt), variants },
      });
      burstFrom(btn.current);
      toast(`드롭 #${r.dropId} 초안을 만들었어요. 아래 목록에서 공개해 주세요`, "ok");
      onCreated();
    } catch (err) {
      const ex = err as ApiError;
      setError(firstFieldError(ex.details) ?? ex.message);
    } finally {
      setSaving(false);
    }
  };

  const setVariant = (i: number, patch: Partial<(typeof variants)[number]>) => setVariants(variants.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  return (
    <form className="fq-card is-pad fq-form fq-admin" onSubmit={submit}>
      <span className="fq-eyebrow">드롭 초안 만들기</span>
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
          {isAdmin ? null : <p className="fq-hint">주최자는 08시부터 22시까지 정각만 고를 수 있어요</p>}
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
          <input className="fq-input" aria-label="SKU" placeholder="SKU" required value={v.skuCode} onChange={(e) => setVariant(i, { skuCode: e.target.value })} />
          <input className="fq-input" aria-label="옵션명" placeholder="옵션명" required value={v.optionName} onChange={(e) => setVariant(i, { optionName: e.target.value })} />
          <input className="fq-input" aria-label="가격" type="number" min={1} required value={v.priceAmount} onChange={(e) => setVariant(i, { priceAmount: Number(e.target.value) })} />
          <input className="fq-input" aria-label="수량" type="number" min={1} required value={v.totalQuantity} onChange={(e) => setVariant(i, { totalQuantity: Number(e.target.value) })} />
          <button type="button" className="fq-btn is-ghost is-sm" aria-label="옵션 삭제" disabled={variants.length === 1} onClick={() => setVariants(variants.filter((_, j) => j !== i))}>
            ✕
          </button>
        </div>
      ))}

      {error ? (
        <p className="fq-err" role="alert">
          {error}
        </p>
      ) : null}
      <button ref={btn} className="fq-btn is-grad" disabled={saving}>
        {saving ? "만드는 중…" : "초안 만들기"}
      </button>
    </form>
  );
}
