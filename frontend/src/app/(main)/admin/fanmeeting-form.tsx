"use client";

import { useRef, useState } from "react";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { burstFrom } from "@/lib/confetti";
import { won } from "@/lib/format";
import type { FanMeetingDetail } from "@/lib/types";
import { fmt, iso, local, toLocal } from "./form-utils";

export type FmForm = {
  title: string;
  entryOpensAt: string;
  firstNoticeScheduledAt: string;
  meetingAt: string;
  winnerLimit: number;
  reserveLimit: number;
  feeAmount: number;
  guerrilla: boolean;
};
type FmErrors = Partial<Record<keyof FmForm, string>>;

const ENTRY_WINDOW = 10 * 60_000; // 응모는 오픈 후 10분
const MEETING_GAP = 48 * 3600_000; // 팬미팅은 응모 마감 48시간 뒤부터


export const validFm = (): FmForm => ({
  title: "NOVA 데뷔 3주년 백스테이지 팬미팅",
  entryOpensAt: local(2 * 86400_000),
  firstNoticeScheduledAt: local(3600_000),
  meetingAt: local(5 * 86400_000),
  winnerLimit: 300,
  reserveLimit: 100,
  feeAmount: 50000,
  guerrilla: false,
});

/** 백엔드와 같은 규칙으로 미리 검사해 필드 바로 아래에 알려 준다 */
function validateFm(f: FmForm): FmErrors {
  const e: FmErrors = {};
  if (!f.title.trim()) e.title = "팬미팅 제목을 입력해 주세요.";
  if (!Number.isInteger(f.winnerLimit) || f.winnerLimit < 1) e.winnerLimit = "당첨 인원은 1명 이상이어야 해요.";
  if (!Number.isInteger(f.reserveLimit) || f.reserveLimit < 0) e.reserveLimit = "예비 인원은 0명 이상이어야 해요.";
  if (!Number.isFinite(f.feeAmount) || f.feeAmount < 0) e.feeAmount = "참가비는 0원 이상이어야 해요.";
  const open = Date.parse(f.entryOpensAt);
  const notice = Date.parse(f.firstNoticeScheduledAt);
  const meet = Date.parse(f.meetingAt);
  if (Number.isNaN(open)) e.entryOpensAt = "응모 오픈 시각을 정해 주세요.";
  else if (open <= Date.now()) e.entryOpensAt = "응모 오픈 시각은 지금 이후여야 해요.";
  if (Number.isNaN(notice)) e.firstNoticeScheduledAt = "공지 시각을 정해 주세요.";
  else if (!Number.isNaN(open) && notice > open) e.firstNoticeScheduledAt = "공지 시각은 오픈 시각과 같거나 앞이어야 해요.";
  if (Number.isNaN(meet)) e.meetingAt = "팬미팅 일시를 정해 주세요.";
  else if (!Number.isNaN(open) && meet < open + ENTRY_WINDOW + MEETING_GAP)
    e.meetingAt = `팬미팅은 응모 마감 48시간 뒤(${fmt(open + ENTRY_WINDOW + MEETING_GAP)})부터 정할 수 있어요.`;
  return e;
}

const FM_FIELD: Record<string, keyof FmForm> = {
  title: "title",
  entryOpensAt: "entryOpensAt",
  firstNoticeScheduledAt: "firstNoticeScheduledAt",
  meetingAt: "meetingAt",
  winnerLimit: "winnerLimit",
  reserveLimit: "reserveLimit",
  feeAmount: "feeAmount",
};

/**
 * 팬미팅 만들기와 고치기에 같이 쓰는 폼.
 * eventId가 있으면 PUT /api/organizer/fan-meetings/{eventId}로 전체 설정을 다시 보낸다.
 */
export function FanMeetingForm({ eventId, initial, onSaved, onCreated }: { eventId?: number; initial?: FmForm; onSaved?: (r: FanMeetingDetail) => void; onCreated?: () => void } = {}) {
  const editing = eventId !== undefined;
  const toast = useToast();
  const btn = useRef<HTMLButtonElement>(null);
  const [f, setF] = useState<FmForm>(() => initial ?? validFm());
  const [errors, setErrors] = useState<FmErrors>({});
  const [created, setCreated] = useState<{ eventId: number } | null>(null);

  const set = <K extends keyof FmForm>(k: K, v: FmForm[K]) => {
    const next = { ...f, [k]: v };
    setF(next);
    // 이미 오류가 보이는 상태라면 고치는 즉시 다시 검사한다
    if (Object.keys(errors).length) setErrors(validateFm(next));
  };

  const open = Date.parse(f.entryOpensAt);
  const closes = Number.isNaN(open) ? null : open + ENTRY_WINDOW;

  const fillInvalid = () => {
    const base = Date.now() + 2 * 86400_000;
    const bad: FmForm = {
      ...f,
      entryOpensAt: toLocal(base),
      firstNoticeScheduledAt: toLocal(base + 13 * 3600_000),
      meetingAt: toLocal(base + 21 * 3600_000),
      winnerLimit: 0,
      reserveLimit: -1,
      feeAmount: -500,
    };
    setF(bad);
    setErrors(validateFm(bad));
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errs = validateFm(f);
    setErrors(errs);
    if (Object.keys(errs).length) {
      toast("입력한 설정에 오류가 있어요", "err");
      document.getElementById(`f-${Object.keys(errs)[0]}`)?.focus();
      return;
    }
    try {
      const body = {
        title: f.title.trim(),
        entryOpensAt: iso(f.entryOpensAt),
        firstNoticeScheduledAt: iso(f.firstNoticeScheduledAt),
        meetingAt: iso(f.meetingAt),
        winnerLimit: f.winnerLimit,
        reserveLimit: f.reserveLimit,
        feeAmount: f.feeAmount,
        opensAtPublic: !f.guerrilla,
        promotionClosesAt: null,
      };
      if (editing) {
        const r = await api<FanMeetingDetail>(`/api/organizer/fan-meetings/${eventId}`, { method: "PUT", body });
        onSaved?.(r);
        toast(`팬미팅 #${r.eventId} 설정을 저장했어요`, "ok");
        return;
      }
      const r = await api<{ eventId: number }>("/api/organizer/fan-meetings", { method: "POST", body });
      setCreated(r);
      burstFrom(btn.current);
      toast(`팬미팅 #${r.eventId}을 만들었어요`, "ok");
      onCreated?.();
    } catch (err) {
      const ex = err as ApiError;
      // 백엔드 검증 오류(details.fields)는 해당 필드 아래에 붙인다
      const fields = ex.details?.fields as Record<string, string> | undefined;
      if (fields) {
        const mapped: FmErrors = {};
        Object.entries(fields).forEach(([k, v]) => {
          if (FM_FIELD[k]) mapped[FM_FIELD[k]] = v;
        });
        setErrors(mapped);
      }
      toast(ex.message, "err");
    }
  };

  const field = (k: keyof FmForm) => ({
    id: `f-${k}`,
    "aria-invalid": !!errors[k],
    "aria-describedby": errors[k] ? `f-${k}-err` : undefined,
  });
  const err = (k: keyof FmForm) =>
    errors[k] ? (
      <p id={`f-${k}-err`} className="fq-err">
        {errors[k]}
      </p>
    ) : null;

  return (
    <form className="fq-card is-pad fq-form fq-admin" onSubmit={submit} noValidate>
      <div className="fq-between">
        <span className="fq-eyebrow">{editing ? `팬미팅 #${eventId} 설정 고치기` : "공연 연계 팬미팅 만들기"}</span>
        {editing ? null : <div className="fq-row">
          <button type="button" className="fq-btn is-ghost is-sm" onClick={fillInvalid}>
            오류 확인용 값 채우기
          </button>
          <button
            type="button"
            className="fq-btn is-ghost is-sm"
            onClick={() => {
              setF(validFm());
              setErrors({});
            }}
          >
            예시 값 채우기
          </button>
        </div>}
      </div>
      <div className="fq-field">
        <label htmlFor="f-title">행사명</label>
        <input {...field("title")} className="fq-input" maxLength={200} value={f.title} onChange={(e) => set("title", e.target.value)} placeholder="NOVA 데뷔 3주년 팬미팅" />
        {err("title")}
      </div>
      <div className="fq-form-2">
        <div className="fq-field">
          <label htmlFor="f-entryOpensAt">응모 오픈 시각</label>
          <p className="fq-hint">응모 마감은 오픈 10분 뒤로 자동 계산돼요{closes ? ` → ${fmt(closes)}` : ""}</p>
          <input {...field("entryOpensAt")} className="fq-input" type="datetime-local" value={f.entryOpensAt} onChange={(e) => set("entryOpensAt", e.target.value)} />
          {err("entryOpensAt")}
        </div>
        <div className="fq-field">
          <label htmlFor="f-firstNoticeScheduledAt">공지 시각</label>
          <p className="fq-hint">오픈 시각과 같거나 앞이어야 해요</p>
          <input {...field("firstNoticeScheduledAt")} className="fq-input" type="datetime-local" value={f.firstNoticeScheduledAt} onChange={(e) => set("firstNoticeScheduledAt", e.target.value)} />
          {err("firstNoticeScheduledAt")}
        </div>
      </div>
      <div className="fq-field">
        <label htmlFor="f-meetingAt">팬미팅 일시</label>
        <p className="fq-hint">응모 마감으로부터 최소 48시간 뒤{closes ? ` (${fmt(closes + MEETING_GAP)} 이후)` : ""}</p>
        <input {...field("meetingAt")} className="fq-input" type="datetime-local" value={f.meetingAt} onChange={(e) => set("meetingAt", e.target.value)} />
        {err("meetingAt")}
      </div>
      <div className="fq-form-2" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))" }}>
        <div className="fq-field">
          <label htmlFor="f-winnerLimit">당첨 인원 (1명 이상)</label>
          <input {...field("winnerLimit")} className="fq-input" type="number" value={f.winnerLimit} onChange={(e) => set("winnerLimit", Number(e.target.value))} />
          {err("winnerLimit")}
        </div>
        <div className="fq-field">
          <label htmlFor="f-reserveLimit">예비 인원 (0명 이상)</label>
          <input {...field("reserveLimit")} className="fq-input" type="number" value={f.reserveLimit} onChange={(e) => set("reserveLimit", Number(e.target.value))} />
          {err("reserveLimit")}
        </div>
        <div className="fq-field">
          <label htmlFor="f-feeAmount">참가비 ({f.feeAmount >= 0 ? won(f.feeAmount) : "-"})</label>
          <input {...field("feeAmount")} className="fq-input" type="number" step={1000} value={f.feeAmount} onChange={(e) => set("feeAmount", Number(e.target.value))} />
          {err("feeAmount")}
        </div>
      </div>
      <label className="fq-agree">
        <input type="checkbox" checked={f.guerrilla} onChange={(e) => set("guerrilla", e.target.checked)} style={{ accentColor: "var(--ux-accent)" }} />
        <span>
          <b>게릴라 오픈 행사로 설정</b>
          <br />
          <span className="fq-muted">켜면 응모가 열리기 전까지 오픈 시각을 ‘곧 공개’로 숨겨요.</span>
        </span>
      </label>
      <div className="fq-row">
        <button ref={btn} className="fq-btn is-grad">
          {editing ? "설정 저장" : "팬미팅 만들기"}
        </button>
        {created ? <span className="fq-chip is-mint">#{created.eventId}, SCHEDULED</span> : null}
      </div>
    </form>
  );
}

