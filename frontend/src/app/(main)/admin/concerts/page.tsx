"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { dateOnly, dateTime } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { OrganizerConcert, OrganizerConcertPage } from "@/lib/types";
import AdminGate from "../admin-gate";
import { firstFieldError } from "../form-utils";
import { concertStage } from "./status";

export default function AdminConcertsPage() {
  return (
    <AdminGate lead={<>공연을 초안으로 만들고 가격, 회차를 정한 뒤 공개해요. 주최자는 본인 공연만, 관리자는 모든 공연을 봐요.</>}>
      <CreateConcert />
      <MyConcerts />
    </AdminGate>
  );
}

const day = (offset: number) => new Date(Date.now() + offset * 86400_000).toLocaleDateString("sv-SE");

function CreateConcert() {
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState({ venueId: 1, layoutId: 1, title: "", description: "", periodStartDate: day(14), periodEndDate: day(15), durationMinutes: 120 });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!f.title.trim()) return setError("공연 제목을 입력해 주세요.");
    if ((f.periodStartDate === "") !== (f.periodEndDate === "")) return setError("공연 기간은 시작일과 종료일을 함께 정해 주세요.");
    if (f.periodStartDate && f.periodStartDate > f.periodEndDate) return setError("공연 종료일은 시작일과 같거나 뒤여야 해요.");
    setError(null);
    setSaving(true);
    try {
      const r = await api<OrganizerConcert>("/api/organizer/concerts", {
        method: "POST",
        body: {
          venueId: f.venueId,
          layoutId: f.layoutId,
          title: f.title.trim(),
          description: f.description || null,
          periodStartDate: f.periodStartDate || null,
          periodEndDate: f.periodEndDate || null,
          durationMinutes: f.durationMinutes || null,
        },
      });
      toast(`공연 #${r.concertId} 초안을 만들었어요`, "ok");
      router.push(`/admin/concerts/${r.concertId}`);
    } catch (err) {
      const ex = err as ApiError;
      setError(firstFieldError(ex.details) ?? ex.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="fq-card is-pad fq-form fq-admin" onSubmit={submit} noValidate>
      <span className="fq-eyebrow">공연 초안 만들기</span>
      <div className="fq-field">
        <label htmlFor="c-title">공연 제목</label>
        <input id="c-title" className="fq-input" maxLength={200} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="NOVA 월드투어 서울" />
      </div>
      <div className="fq-field">
        <label htmlFor="c-desc">소개</label>
        <textarea id="c-desc" className="fq-input" maxLength={5000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
      </div>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="c-venue">공연장 ID</label>
          <input id="c-venue" className="fq-input" type="number" min={1} value={f.venueId} onChange={(e) => setF({ ...f, venueId: Number(e.target.value) })} />
        </div>
        <div className="fq-field">
          <label htmlFor="c-layout">좌석 배치 ID</label>
          <p className="fq-hint">공연장에 속한 배치여야 해요. 만든 뒤 상세 화면에서 배치 목록을 보고 바꿀 수 있어요</p>
          <input id="c-layout" className="fq-input" type="number" min={1} value={f.layoutId} onChange={(e) => setF({ ...f, layoutId: Number(e.target.value) })} />
        </div>
      </div>
      <div className="fq-form-2" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))" }}>
        <div className="fq-field">
          <label htmlFor="c-start">공연 시작일</label>
          <input id="c-start" className="fq-input" type="date" value={f.periodStartDate} onChange={(e) => setF({ ...f, periodStartDate: e.target.value })} />
        </div>
        <div className="fq-field">
          <label htmlFor="c-end">공연 종료일</label>
          <input id="c-end" className="fq-input" type="date" value={f.periodEndDate} onChange={(e) => setF({ ...f, periodEndDate: e.target.value })} />
        </div>
        <div className="fq-field">
          <label htmlFor="c-dur">공연 시간 (분)</label>
          <input id="c-dur" className="fq-input" type="number" min={1} value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: Number(e.target.value) })} />
        </div>
      </div>
      {error ? (
        <p className="fq-err" role="alert">
          {error}
        </p>
      ) : null}
      <button className="fq-btn is-grad" disabled={saving}>
        {saving ? "만드는 중…" : "초안 만들기"}
      </button>
    </form>
  );
}

function MyConcerts() {
  const { data, error } = useLoad(() => api<OrganizerConcertPage>("/api/organizer/concerts?size=100"));
  return (
    <section className="fq-section" style={{ marginTop: 22 }}>
      <span className="fq-eyebrow">내 공연</span>
      {error ? (
        <LoadError error={error} backHref="/admin" />
      ) : !data ? (
        <div className="fq-skel" style={{ height: 200 }} />
      ) : data.content.length === 0 ? (
        <div className="fq-card is-pad fq-empty">
          <LineArt kind="ticket" />
          <p>아직 만든 공연이 없어요.</p>
        </div>
      ) : (
        <ul className="fq-orders" style={{ marginTop: 12 }}>
          {data.content.map((c) => {
            const s = concertStage(c);
            return (
              <li key={c.concertId} className="fq-card fq-order">
                <div className="fq-order-b">
                  <div className="fq-row">
                    <span className={`fq-chip ${s.tone}`}>{s.label}</span>
                  </div>
                  <h3>{c.title}</h3>
                  <small>
                    #{c.concertId}, {c.venueName}, {c.periodStartDate && c.periodEndDate ? `${dateOnly(c.periodStartDate)} ~ ${dateOnly(c.periodEndDate)}` : "기간 미정"}, 만든 날 {dateTime(c.createdAt)}
                  </small>
                </div>
                <div className="fq-order-r">
                  <Link className="fq-btn is-ghost is-sm" href={`/admin/concerts/${c.concertId}`}>
                    관리
                  </Link>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
