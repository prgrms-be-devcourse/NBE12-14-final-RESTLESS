"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { burstFrom } from "@/lib/confetti";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import type { OrganizerConcert, OrganizerLayout, PerformanceInfo, PerformanceSetup, SectionPrice } from "@/lib/types";
import AdminGate from "../../admin-gate";
import { firstFieldError, iso, isoToLocal, toLocal } from "../../form-utils";
import { PRICING_STATUS, concertStage } from "../status";

const errText = (err: unknown) => {
  const ex = err as ApiError;
  if (ex.code === "CONCERT_DELETE_CONFLICT") return "공개한 적이 있거나 연결된 회차, 선점, 드롭, 경매가 있어 삭제할 수 없어요.";
  if (ex.code === "TICKET_SCHEDULE_CHANGE_CLOSED") return "예매가 시작됐거나 선점 이력이 있는 회차가 있어 일정을 바꿀 수 없어요.";
  return firstFieldError(ex.details) ?? ex.message;
};

export default function ConcertManageView({ id }: { id: number }) {
  return (
    <AdminGate lead={<>공연 정보, 좌석 배치, 예매 오픈, 구역 가격, 회차 순서로 정하고 공개해요. 가격과 예매 오픈은 회차를 추가하기 전에 정해야 하고, 공개한 뒤에는 일정과 가격을 바꿀 수 없어요.</>}>
      <Manage id={id} />
    </AdminGate>
  );
}

function Manage({ id }: { id: number }) {
  const toast = useToast();
  const router = useRouter();
  const publishBtn = useRef<HTMLButtonElement>(null);
  const concert = useLoad(() => api<OrganizerConcert>(`/api/organizer/concerts/${id}`), [id]);
  const layouts = useLoad(() => api<OrganizerLayout[]>(`/api/organizer/concerts/${id}/layouts`), [id, concert.data?.venueId]);
  const perfs = useLoad(() => api<PerformanceSetup[]>(`/api/organizer/concerts/${id}/performances`), [id]);
  const [busy, setBusy] = useState(false);

  if (concert.error) return <LoadError error={concert.error} backHref="/admin/concerts" />;
  const c = concert.data;
  if (!c) return <div className="fq-skel" style={{ height: 300 }} />;
  const stage = concertStage(c);
  const reloadAll = () => Promise.all([concert.reload(), layouts.reload(), perfs.reload()]).catch(() => undefined);

  const publish = async () => {
    setBusy(true);
    try {
      concert.setData(await api<OrganizerConcert>(`/api/organizer/concerts/${id}/publish`, { method: "POST" }));
      burstFrom(publishBtn.current);
      toast("공연을 공개했어요", "ok");
      void perfs.reload().catch(() => undefined);
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!confirm(`‘${c.title}’ 공연을 삭제할까요? 되돌릴 수 없어요.`)) return;
    setBusy(true);
    try {
      await api(`/api/organizer/concerts/${id}`, { method: "DELETE" });
      toast(`공연 #${id}을 삭제했어요`, "ok");
      router.push("/admin/concerts");
    } catch (err) {
      toast(errText(err), "err");
      setBusy(false);
    }
  };

  return (
    <>
      <section className="fq-card is-pad">
        <div className="fq-between">
          <span className="fq-eyebrow">공연 #{c.concertId}</span>
          <span className={`fq-chip ${stage.tone}`}>{stage.label}</span>
        </div>
        <h2 style={{ marginTop: 8 }}>{c.title}</h2>
        <p className="fq-muted">
          {c.venueName}, {c.venueAddress}
          {c.publishedAt ? `, ${dateTime(c.publishedAt)} 공개` : ""}
        </p>
        <div className="fq-row" style={{ marginTop: 14 }}>
          <button ref={publishBtn} type="button" className="fq-btn is-hot" disabled={busy || c.published} onClick={publish}>
            {c.published ? "공개됨" : "공개하기"}
          </button>
          {c.published ? (
            <Link className="fq-btn is-ghost" href={`/concerts/detail?id=${c.concertId}`}>
              공개 화면 보기
            </Link>
          ) : null}
          <button type="button" className="fq-btn is-danger" disabled={busy || c.published || !!c.publishedAt} onClick={remove}>
            공연 삭제
          </button>
        </div>
        {!c.published ? <p className="fq-hint" style={{ marginTop: 10 }}>공개하려면 기간, 공연 시간, 예매 오픈, 모든 구역 가격, 회차가 하나 이상 있어야 하고, 예매 오픈 전이어야 해요.</p> : null}
      </section>

      <DetailsForm c={c} onSaved={(next) => concert.setData(next)} />
      <VenueLayout c={c} layouts={layouts} onSaved={(next) => { concert.setData(next); void reloadAll(); }} />
      <BookingOpens c={c} onSaved={(next) => { concert.setData(next); void perfs.reload().catch(() => undefined); }} />
      <SectionPrices c={c} layouts={layouts.data} onSaved={() => void concert.reload().catch(() => undefined)} />
      <Performances c={c} perfs={perfs} />
    </>
  );
}

function DetailsForm({ c, onSaved }: { c: OrganizerConcert; onSaved: (c: OrganizerConcert) => void }) {
  const toast = useToast();
  const [f, setF] = useState({
    title: c.title,
    description: c.description ?? "",
    periodStartDate: c.periodStartDate ?? "",
    periodEndDate: c.periodEndDate ?? "",
    durationMinutes: c.durationMinutes ?? 0,
  });
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
      const r = await api<OrganizerConcert>(`/api/organizer/concerts/${c.concertId}`, {
        method: "PUT",
        body: {
          title: f.title.trim(),
          description: f.description || null,
          periodStartDate: f.periodStartDate || null,
          periodEndDate: f.periodEndDate || null,
          durationMinutes: f.durationMinutes || null,
        },
      });
      onSaved(r);
      toast("공연 정보를 저장했어요", "ok");
    } catch (err) {
      setError(errText(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <form className="fq-card is-pad fq-form fq-admin" style={{ marginTop: 18 }} onSubmit={submit} noValidate>
      <span className="fq-eyebrow">공연 정보</span>
      <div className="fq-field">
        <label htmlFor="cd-title">공연 제목</label>
        <input id="cd-title" className="fq-input" maxLength={200} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />
      </div>
      <div className="fq-field">
        <label htmlFor="cd-desc">소개</label>
        <textarea id="cd-desc" className="fq-input" maxLength={5000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
      </div>
      <div className="fq-form-2" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))" }}>
        <div className="fq-field">
          <label htmlFor="cd-start">공연 시작일</label>
          <input id="cd-start" className="fq-input" type="date" disabled={c.published} value={f.periodStartDate} onChange={(e) => setF({ ...f, periodStartDate: e.target.value })} />
        </div>
        <div className="fq-field">
          <label htmlFor="cd-end">공연 종료일</label>
          <input id="cd-end" className="fq-input" type="date" disabled={c.published} value={f.periodEndDate} onChange={(e) => setF({ ...f, periodEndDate: e.target.value })} />
        </div>
        <div className="fq-field">
          <label htmlFor="cd-dur">공연 시간 (분)</label>
          <input id="cd-dur" className="fq-input" type="number" min={1} disabled={c.published} value={f.durationMinutes || ""} onChange={(e) => setF({ ...f, durationMinutes: Number(e.target.value) })} />
        </div>
      </div>
      {c.published ? <p className="fq-hint">공개한 공연은 제목과 소개만 바꿀 수 있어요.</p> : <p className="fq-hint">회차가 있으면 모든 회차가 새 기간 안에 있어야 하고, 회차끼리 공연 시간만큼 떨어져 있어야 해요.</p>}
      {error ? (
        <p className="fq-err" role="alert">
          {error}
        </p>
      ) : null}
      <button className="fq-btn is-grad" disabled={saving}>
        {saving ? "저장 중…" : "정보 저장"}
      </button>
    </form>
  );
}

function VenueLayout({ c, layouts, onSaved }: { c: OrganizerConcert; layouts: { data: OrganizerLayout[] | null; error: ApiError | null }; onSaved: (c: OrganizerConcert) => void }) {
  const toast = useToast();
  const [venueId, setVenueId] = useState(c.venueId);
  const [layoutId, setLayoutId] = useState(c.layoutId);
  const [saving, setSaving] = useState(false);
  const locked = c.published || !!c.publishedAt;
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!confirm("공연장이나 배치를 바꾸면 이미 정한 구역 가격이 지워져요. 바꿀까요?")) return;
    setSaving(true);
    try {
      const r = await api<OrganizerConcert>(`/api/organizer/concerts/${c.concertId}/venue-layout`, { method: "PUT", body: { venueId, layoutId } });
      onSaved(r);
      toast("공연장과 배치를 바꿨어요", "ok");
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setSaving(false);
    }
  };
  return (
    <form className="fq-card is-pad fq-form fq-admin" style={{ marginTop: 18 }} onSubmit={submit}>
      <span className="fq-eyebrow">공연장, 좌석 배치</span>
      <p className="fq-muted">
        지금: {c.venueName} (공연장 #{c.venueId}), 배치 #{c.layoutId}
      </p>
      {layouts.error ? (
        <p className="fq-err">배치 목록을 불러오지 못했어요. {layouts.error.message}</p>
      ) : !layouts.data ? (
        <div className="fq-skel" style={{ height: 80 }} />
      ) : layouts.data.length === 0 ? (
        <p className="fq-muted">이 공연장에 등록된 배치가 없어요.</p>
      ) : (
        <ul className="fq-list">
          {layouts.data.map((l) => (
            <li key={l.layoutId}>
              <label className="fq-agree" style={{ width: "100%" }}>
                <input type="radio" name="layout" checked={venueId === c.venueId && layoutId === l.layoutId} disabled={locked} onChange={() => { setVenueId(c.venueId); setLayoutId(l.layoutId); }} />
                <span>
                  <b>
                    #{l.layoutId} {l.name}
                  </b>{" "}
                  <span className="fq-chip">{l.status}</span>
                  <br />
                  <span className="fq-muted">{l.sections.map((s) => `${s.sectionLabel} ${s.sellableSeats}석`).join(", ") || "판매 좌석 없음"}</span>
                </span>
              </label>
            </li>
          ))}
        </ul>
      )}
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="vl-venue">공연장 ID</label>
          <p className="fq-hint">다른 공연장으로 옮길 때만 바꿔요. 목록은 지금 공연장의 배치예요</p>
          <input id="vl-venue" className="fq-input" type="number" min={1} disabled={locked} value={venueId} onChange={(e) => setVenueId(Number(e.target.value))} />
        </div>
        <div className="fq-field">
          <label htmlFor="vl-layout">배치 ID</label>
          <input id="vl-layout" className="fq-input" type="number" min={1} disabled={locked} value={layoutId} onChange={(e) => setLayoutId(Number(e.target.value))} />
        </div>
      </div>
      {locked ? <p className="fq-hint">공개한 적이 있는 공연은 공연장과 배치를 바꿀 수 없어요.</p> : <p className="fq-hint">회차가 없을 때만 바꿀 수 있어요.</p>}
      <button className="fq-btn is-ghost" disabled={saving || locked || (venueId === c.venueId && layoutId === c.layoutId)}>
        {saving ? "바꾸는 중…" : "공연장, 배치 바꾸기"}
      </button>
    </form>
  );
}

function SectionPrices({ c, layouts, onSaved }: { c: OrganizerConcert; layouts: OrganizerLayout[] | null; onSaved: () => void }) {
  const toast = useToast();
  const layout = layouts?.find((l) => l.layoutId === c.layoutId) ?? null;
  const [rows, setRows] = useState<Record<string, { grade: string; price: number }>>({});
  const [saved, setSaved] = useState<SectionPrice[] | null>(null);
  const [saving, setSaving] = useState(false);
  const row = (label: string) => rows[label] ?? { grade: "R", price: 110000 };
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!layout) return;
    const sections = layout.sections.map((s) => ({ sectionLabel: s.sectionLabel, grade: row(s.sectionLabel).grade.trim(), price: row(s.sectionLabel).price }));
    if (sections.some((s) => !s.grade || s.price < 1)) return toast("모든 구역에 등급과 1원 이상의 가격을 입력해 주세요.", "err");
    setSaving(true);
    try {
      setSaved(await api<SectionPrice[]>(`/api/organizer/concerts/${c.concertId}/section-prices`, { method: "PUT", body: { sections } }));
      toast("구역 가격을 저장했어요", "ok");
      onSaved();
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setSaving(false);
    }
  };
  return (
    <form className="fq-card is-pad fq-form fq-admin" style={{ marginTop: 18 }} onSubmit={submit}>
      <div className="fq-between">
        <span className="fq-eyebrow">구역별 가격</span>
        <span className={`fq-chip ${c.pricingConfigured ? "is-mint" : "is-mute"}`}>{c.pricingConfigured ? "설정됨" : "미설정"}</span>
      </div>
      <p className="fq-hint">배치의 판매 구역마다 등급과 가격을 모두 정해요. 다시 저장하면 전체를 바꿔요. 회차를 추가한 뒤나 공개한 뒤에는 바꿀 수 없어요.</p>
      {!layouts ? (
        <div className="fq-skel" style={{ height: 80 }} />
      ) : !layout ? (
        <p className="fq-muted">지금 배치(#{c.layoutId})의 구역 정보를 찾지 못했어요.</p>
      ) : layout.sections.length === 0 ? (
        <p className="fq-muted">이 배치에는 판매할 수 있는 좌석이 없어요.</p>
      ) : (
        layout.sections.map((s) => (
          <div key={s.sectionLabel} className="fq-variant-row" style={{ gridTemplateColumns: "1fr 1fr 1fr" }}>
            <span>
              <b>{s.sectionLabel.endsWith("구역") ? s.sectionLabel : `${s.sectionLabel}구역`}</b> <small className="fq-muted">{s.sellableSeats}석</small>
            </span>
            <input className="fq-input" aria-label={`${s.sectionLabel} 등급`} maxLength={20} disabled={c.published} value={row(s.sectionLabel).grade} onChange={(e) => setRows({ ...rows, [s.sectionLabel]: { ...row(s.sectionLabel), grade: e.target.value } })} />
            <input className="fq-input" aria-label={`${s.sectionLabel} 가격`} type="number" min={1} step={1} disabled={c.published} value={row(s.sectionLabel).price} onChange={(e) => setRows({ ...rows, [s.sectionLabel]: { ...row(s.sectionLabel), price: Number(e.target.value) } })} />
          </div>
        ))
      )}
      {saved ? <p className="fq-muted">저장됨: {saved.map((p) => `${p.sectionLabel} ${p.grade} ${won(p.price)}`).join(", ")}</p> : null}
      <button className="fq-btn is-grad" disabled={saving || c.published || !layout?.sections.length}>
        {saving ? "저장 중…" : "가격 저장"}
      </button>
    </form>
  );
}

/** 예매 시작은 회차마다 받지 않는다. 서버가 공연의 예매 오픈을 모든 회차에 복사한다 */
type PerfForm = { startsAt: string; bookingClosesAt: string; maxTicketsPerUser: number };

const defaultPerf = (c: OrganizerConcert): PerfForm => {
  // 공연 시작일 19시, 예매 마감은 공연 하루 전
  const base = c.periodStartDate ? new Date(`${c.periodStartDate}T19:00`).getTime() : Date.now() + 14 * 86400_000;
  return { startsAt: toLocal(base), bookingClosesAt: toLocal(base - 86400_000), maxTicketsPerUser: 4 };
};

/** 백엔드와 같은 기본 규칙: 공연 예매 오픈 < 예매 마감 <= 공연 시작, 1인 한도 1 이상 */
const checkPerf = (p: PerfForm, bookingOpensAt: string | null) => {
  if (!bookingOpensAt) return "공연 예매 오픈 시각을 먼저 정해 주세요.";
  const [s, o, cl] = [Date.parse(p.startsAt), Date.parse(bookingOpensAt), Date.parse(p.bookingClosesAt)];
  if ([s, cl].some(Number.isNaN)) return "공연 시작과 예매 마감을 모두 정해 주세요.";
  if (o >= cl) return "예매 마감은 공연 예매 오픈보다 뒤여야 해요.";
  if (cl > s) return "예매 마감은 공연 시작과 같거나 앞이어야 해요.";
  if (!Number.isInteger(p.maxTicketsPerUser) || p.maxTicketsPerUser < 1) return "1인 구매 한도는 1장 이상이어야 해요.";
  return null;
};

const perfBody = (p: PerfForm) => ({ startsAt: iso(p.startsAt), bookingClosesAt: iso(p.bookingClosesAt), maxTicketsPerUser: p.maxTicketsPerUser });

/** 공연 단위 예매 오픈. 바꾸면 모든 회차의 예매 시작이 함께 바뀐다 */
function BookingOpens({ c, onSaved }: { c: OrganizerConcert; onSaved: (c: OrganizerConcert) => void }) {
  const toast = useToast();
  const [value, setValue] = useState(() => isoToLocal(c.bookingOpensAt));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // 공개한 공연은 예매 오픈 전이고 새 시각도 미래일 때만 바꿀 수 있다
  const locked = c.published && (!c.bookingOpensAt || Date.parse(c.bookingOpensAt) <= Date.now());
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const t = Date.parse(value);
    if (Number.isNaN(t)) return setError("예매 오픈 시각을 정해 주세요.");
    if (c.published && t <= Date.now()) return setError("공개한 공연은 지금 이후로만 바꿀 수 있어요.");
    setError(null);
    setSaving(true);
    try {
      const r = await api<OrganizerConcert>(`/api/organizer/concerts/${c.concertId}/booking-opens-at`, { method: "PUT", body: { bookingOpensAt: iso(value) } });
      toast("예매 오픈 시각을 저장했어요", "ok");
      onSaved(r);
    } catch (err) {
      setError(errText(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <form className="fq-card is-pad fq-form" style={{ marginTop: 18 }} onSubmit={submit} noValidate>
      <span className="fq-eyebrow">예매 오픈</span>
      <p className="fq-muted">
        {c.bookingOpensAt ? `${dateTime(c.bookingOpensAt)}에 모든 회차 예매가 열려요.` : "아직 정하지 않았어요. 회차를 추가하기 전에 정해 주세요."}
      </p>
      <div className="fq-field">
        <label htmlFor="cd-opens">예매 오픈 시각</label>
        <input id="cd-opens" className="fq-input" type="datetime-local" disabled={locked} value={value} onChange={(e) => setValue(e.target.value)} />
      </div>
      <p className="fq-hint">
        {locked ? "예매가 이미 열려 바꿀 수 없어요." : "모든 회차의 예매 마감보다 앞이어야 해요. 선점 이력이 있는 회차가 있으면 바꿀 수 없어요."}
      </p>
      {error ? (
        <p className="fq-err" role="alert">
          {error}
        </p>
      ) : null}
      <button className="fq-btn is-grad" disabled={saving || locked}>
        {saving ? "저장 중…" : "예매 오픈 저장"}
      </button>
    </form>
  );
}

function PerfFields({ p, onChange, idPrefix, disabled }: { p: PerfForm; onChange: (p: PerfForm) => void; idPrefix: string; disabled?: boolean }) {
  return (
    <div className="fq-form-2" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))" }}>
      <div className="fq-field">
        <label htmlFor={`${idPrefix}-starts`}>공연 시작</label>
        <input id={`${idPrefix}-starts`} className="fq-input" type="datetime-local" disabled={disabled} value={p.startsAt} onChange={(e) => onChange({ ...p, startsAt: e.target.value })} />
      </div>
      <div className="fq-field">
        <label htmlFor={`${idPrefix}-closes`}>예매 마감</label>
        <input id={`${idPrefix}-closes`} className="fq-input" type="datetime-local" disabled={disabled} value={p.bookingClosesAt} onChange={(e) => onChange({ ...p, bookingClosesAt: e.target.value })} />
      </div>
      <div className="fq-field">
        <label htmlFor={`${idPrefix}-max`}>1인 구매 한도</label>
        <input id={`${idPrefix}-max`} className="fq-input" type="number" min={1} disabled={disabled} value={p.maxTicketsPerUser} onChange={(e) => onChange({ ...p, maxTicketsPerUser: Number(e.target.value) })} />
      </div>
    </div>
  );
}

function Performances({ c, perfs }: { c: OrganizerConcert; perfs: { data: PerformanceSetup[] | null; error: ApiError | null; reload: () => Promise<unknown> } }) {
  const toast = useToast();
  const [editing, setEditing] = useState<{ id: number; form: PerfForm } | null>(null);
  const [busy, setBusy] = useState(false);
  const reload = () => void perfs.reload().catch(() => undefined);
  const list = [...(perfs.data ?? [])].sort((a, b) => +new Date(a.performance.startsAt) - +new Date(b.performance.startsAt));

  const save = async () => {
    if (!editing) return;
    const msg = checkPerf(editing.form, c.bookingOpensAt);
    if (msg) return toast(msg, "err");
    setBusy(true);
    try {
      await api<PerformanceInfo>(`/api/organizer/performances/${editing.id}`, { method: "PUT", body: perfBody(editing.form) });
      toast(`회차 #${editing.id}을 고쳤어요`, "ok");
      setEditing(null);
      reload();
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (p: PerformanceInfo) => {
    if (!confirm(`${dateTime(p.startsAt)} 회차를 삭제할까요?`)) return;
    setBusy(true);
    try {
      await api(`/api/organizer/performances/${p.performanceId}`, { method: "DELETE" });
      toast(`회차 #${p.performanceId}을 삭제했어요`, "ok");
      reload();
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="fq-card is-pad fq-form" style={{ marginTop: 18 }}>
      <span className="fq-eyebrow">회차</span>
      {perfs.error ? (
        <LoadError error={perfs.error} backHref="/admin/concerts" />
      ) : !perfs.data ? (
        <div className="fq-skel" style={{ height: 120 }} />
      ) : list.length === 0 ? (
        <div className="fq-empty">
          <LineArt kind="ticket" size={64} />
          <p className="fq-muted">아직 회차가 없어요. 구역 가격을 정한 뒤 아래에서 추가해 주세요.</p>
        </div>
      ) : (
        <ul className="fq-orders">
          {list.map(({ performance: p, pricingStatus, sellableSeatCount, configuredSeatCount, sections }) => {
            const ps = PRICING_STATUS[pricingStatus] ?? { label: pricingStatus, tone: "" };
            const isEditing = editing?.id === p.performanceId;
            return (
              <li key={p.performanceId} className="fq-card fq-order">
                <div className="fq-order-b" style={{ width: "100%" }}>
                  <div className="fq-row">
                    <span className="fq-chip">{p.status}</span>
                    <span className={`fq-chip ${ps.tone}`}>{ps.label}</span>
                  </div>
                  <h3>
                    #{p.performanceId}, {dateTime(p.startsAt)}
                  </h3>
                  <small>
                    예매 {dateTime(p.bookingOpensAt)} ~ {dateTime(p.bookingClosesAt)}, 1인 {p.maxTicketsPerUser}장, 좌석 {configuredSeatCount}/{sellableSeatCount}
                    {sections.length ? `, ${sections.map((s) => (s.uniform ? `${s.sectionLabel} ${s.grade} ${won(s.price ?? 0)}` : `${s.sectionLabel} 가격 불일치`)).join(", ")}` : ""}
                  </small>
                  {isEditing ? (
                    <div style={{ marginTop: 12 }}>
                      <PerfFields p={editing.form} onChange={(form) => setEditing({ id: p.performanceId, form })} idPrefix={`pe-${p.performanceId}`} />
                      <div className="fq-row">
                        <button type="button" className="fq-btn is-grad is-sm" disabled={busy} onClick={save}>
                          저장
                        </button>
                        <button type="button" className="fq-btn is-ghost is-sm" onClick={() => setEditing(null)}>
                          닫기
                        </button>
                      </div>
                    </div>
                  ) : null}
                </div>
                {isEditing ? null : (
                  <div className="fq-order-r fq-row">
                    {c.published ? (
                      <Link className="fq-btn is-ghost is-sm" href={`/performances?id=${p.performanceId}&concert=${c.concertId}&max=${p.maxTicketsPerUser}`}>
                        좌석
                      </Link>
                    ) : null}
                    <button
                      type="button"
                      className="fq-btn is-ghost is-sm"
                      disabled={busy || c.published}
                      onClick={() => setEditing({ id: p.performanceId, form: { startsAt: isoToLocal(p.startsAt), bookingClosesAt: isoToLocal(p.bookingClosesAt), maxTicketsPerUser: p.maxTicketsPerUser } })}
                    >
                      고치기
                    </button>
                    <button type="button" className="fq-btn is-danger is-sm" disabled={busy || c.published || !!c.publishedAt} onClick={() => remove(p)}>
                      삭제
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {c.published ? <p className="fq-hint">공개한 공연에는 회차를 추가하거나 지울 수 없어요.</p> : <AddPerformances c={c} onAdded={reload} />}
    </section>
  );
}

function AddPerformances({ c, onAdded }: { c: OrganizerConcert; onAdded: () => void }) {
  const toast = useToast();
  const [mode, setMode] = useState<"one" | "batch">("one");
  const [one, setOne] = useState<PerfForm>(() => defaultPerf(c));
  const [batch, setBatch] = useState(() => {
    const d = defaultPerf(c);
    return { maxTicketsPerUser: d.maxTicketsPerUser, items: [{ startsAt: d.startsAt, bookingClosesAt: d.bookingClosesAt }] };
  });
  const [busy, setBusy] = useState(false);

  const addOne = async (e: React.FormEvent) => {
    e.preventDefault();
    const msg = checkPerf(one, c.bookingOpensAt);
    if (msg) return toast(msg, "err");
    setBusy(true);
    try {
      const r = await api<PerformanceInfo>(`/api/organizer/concerts/${c.concertId}/performances`, { method: "POST", body: perfBody(one) });
      toast(`회차 #${r.performanceId}을 추가했어요`, "ok");
      onAdded();
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setBusy(false);
    }
  };

  const addBatch = async (e: React.FormEvent) => {
    e.preventDefault();
    for (const item of batch.items) {
      const msg = checkPerf({ ...item, maxTicketsPerUser: batch.maxTicketsPerUser }, c.bookingOpensAt);
      if (msg) return toast(msg, "err");
    }
    setBusy(true);
    try {
      const r = await api<{ performances: PerformanceInfo[] }>(`/api/organizer/concerts/${c.concertId}/performances/batch`, {
        method: "POST",
        body: {
          maxTicketsPerUser: batch.maxTicketsPerUser,
          performances: batch.items.map((it) => ({ startsAt: iso(it.startsAt), bookingClosesAt: iso(it.bookingClosesAt) })),
        },
      });
      toast(`회차 ${r.performances.length}개를 추가했어요`, "ok");
      onAdded();
    } catch (err) {
      toast(errText(err), "err");
    } finally {
      setBusy(false);
    }
  };

  const setItem = (i: number, patch: Partial<{ startsAt: string; bookingClosesAt: string }>) =>
    setBatch({ ...batch, items: batch.items.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  return (
    <div style={{ marginTop: 14 }}>
      <div className="fq-between">
        <span className="fq-eyebrow">회차 추가</span>
        <div className="fq-row">
          <button type="button" className={`fq-btn is-sm ${mode === "one" ? "is-grad" : "is-ghost"}`} onClick={() => setMode("one")}>
            하나씩
          </button>
          <button type="button" className={`fq-btn is-sm ${mode === "batch" ? "is-grad" : "is-ghost"}`} onClick={() => setMode("batch")}>
            여러 개 한 번에
          </button>
        </div>
      </div>
      <p className="fq-hint">
        회차는 공연 기간 안이어야 하고, 앞 회차와 공연 시간({c.durationMinutes ?? "?"}분)만큼 떨어져 있어야 해요. 예매 시작은 공연 예매 오픈({c.bookingOpensAt ? dateTime(c.bookingOpensAt) : "미정"})을 따라요.
      </p>
      {mode === "one" ? (
        <form className="fq-form" onSubmit={addOne}>
          <PerfFields p={one} onChange={setOne} idPrefix="pa" />
          <button className="fq-btn is-grad" disabled={busy}>
            {busy ? "추가 중…" : "회차 추가"}
          </button>
        </form>
      ) : (
        <form className="fq-form" onSubmit={addBatch}>
          <div className="fq-field">
            <label htmlFor="pb-max">공통 1인 구매 한도</label>
            <input id="pb-max" className="fq-input" type="number" min={1} value={batch.maxTicketsPerUser} onChange={(e) => setBatch({ ...batch, maxTicketsPerUser: Number(e.target.value) })} />
          </div>
          <div className="fq-between">
            <span className="fq-muted">회차 {batch.items.length}/10</span>
            <button
              type="button"
              className="fq-btn is-ghost is-sm"
              disabled={batch.items.length >= 10}
              onClick={() => {
                const last = batch.items[batch.items.length - 1];
                const next = Date.parse(last.startsAt) + 86400_000;
                setBatch({ ...batch, items: [...batch.items, { startsAt: toLocal(next), bookingClosesAt: toLocal(next - 86400_000) }] });
              }}
            >
              + 회차 줄 추가
            </button>
          </div>
          {batch.items.map((it, i) => (
            <div key={i} className="fq-variant-row" style={{ gridTemplateColumns: "1fr 1fr auto" }}>
              <input className="fq-input" aria-label={`${i + 1}번째 공연 시작`} type="datetime-local" value={it.startsAt} onChange={(e) => setItem(i, { startsAt: e.target.value })} />
              <input className="fq-input" aria-label={`${i + 1}번째 예매 마감`} type="datetime-local" value={it.bookingClosesAt} onChange={(e) => setItem(i, { bookingClosesAt: e.target.value })} />
              <button type="button" className="fq-btn is-ghost is-sm" aria-label="회차 줄 삭제" disabled={batch.items.length === 1} onClick={() => setBatch({ ...batch, items: batch.items.filter((_, j) => j !== i) })}>
                ✕
              </button>
            </div>
          ))}
          <p className="fq-hint">왼쪽은 공연 시작, 오른쪽은 예매 마감이에요.</p>
          <button className="fq-btn is-grad" disabled={busy}>
            {busy ? "추가 중…" : `회차 ${batch.items.length}개 추가`}
          </button>
        </form>
      )}
    </div>
  );
}
