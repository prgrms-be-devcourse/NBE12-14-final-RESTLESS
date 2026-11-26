"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { FM_STATUS, firstWord } from "@/components/Cards";
import LoadError from "@/components/LoadError";
import Poster from "@/components/Poster";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { countdown, dateTime, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import type { FanMeetingDetail, MyApplication } from "@/lib/types";
import "./fanmeeting.css";

const AFTER_DRAW = ["CLOSED", "DRAWING", "PAYMENT_IN_PROGRESS", "RECRUITMENT_COMPLETED"];

export default function FanMeetingView({ id }: { id: number }) {
  const now = useNow();
  const toast = useToast();
  const { profile } = useAuth();
  const { data: f, error, loading } = useLoad(() => api<FanMeetingDetail>(`/api/fan-meetings/${id}`), [id]);
  const [realMine, setMine] = useState<MyApplication | null>(null);

  const mine = realMine;
  const [busy, setBusy] = useState(false);
  const ctaRef = useRef<HTMLButtonElement>(null);

  // 내 응모 상태 (응모하지 않았으면 404)
  useEffect(() => {
    if (!profile) return;
    api<MyApplication>(`/api/fan-meetings/${id}/applications/me`)
      .then((application) => setMine(application.status === "NONE" ? null : application))
      .catch(() => setMine(null));
  }, [id, profile]);

  const apply = async () => {
    setBusy(true);
    try {
      const application = await api<MyApplication>(`/api/fan-meetings/${id}/applications`, { method: "POST" });
      setMine(application);
      burstFrom(ctaRef.current);
      toast("응모했어요. 결과는 추첨 후 확인할 수 있어요.", "ok");
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "ALREADY_APPLIED") {
        api<MyApplication>(`/api/fan-meetings/${id}/applications/me`).then((value) => setMine(value.status === "NONE" ? null : value)).catch(() => undefined);
        toast("이미 응모한 팬미팅이에요.", "info");
      } else if (err.code === "APPLICATION_BUSY") toast("응모 요청이 몰리고 있어요. 잠시 후 다시 시도해 주세요.", "err");
      else toast(err.message, "err");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    try {
      await api(`/api/fan-meetings/${id}/applications/me`, { method: "DELETE" });
      setMine(null);
      toast("응모를 취소했어요");
    } catch (e) {
      toast((e as ApiError).message, "err");
    }
  };

  const s = f ? FM_STATUS[f.status] ?? { label: f.status, tone: "" } : null;
  // 게릴라 오픈: 응모가 열리기 전까지 오픈 시각을 숨긴다
  const hidden = !!f && f.status === "SCHEDULED" && f.entryOpensAt === null;
  const afterDraw = !!f && AFTER_DRAW.includes(f.status);
  const steps = f
    ? [
        { at: f.entryOpensAt, label: "응모 시작", secret: hidden },
        { at: f.entryClosesAt, label: "응모 마감 (오픈 + 10분)", secret: hidden },
        { at: f.entryClosesAt, label: "추첨, 발표", done: ["DRAWING", "PAYMENT_IN_PROGRESS", "RECRUITMENT_COMPLETED"].includes(f.status) },
        { at: f.meetingAt, label: "팬미팅 당일" },
      ]
    : [];

  if (error) return <LoadError error={error} backHref="/fan-meetings" />;
  if (loading || !f) return <div className="fq-skel" style={{ height: 360 }} />;

  return (
    <div className="fq-detail">
      <Link href="/fan-meetings" className="fq-back">
        ← 팬미팅 목록
      </Link>
      <div className="fq-detail-grid">
        <div className="fq-detail-art">
          {f ? <Poster seed={f.eventId + 4} word={firstWord(f.title)} kicker="FAN MEETING" /> : <div className="fq-skel" style={{ aspectRatio: "4/5" }} />}
        </div>
        <div className="fq-detail-info">
          <div className="fq-row">
            {s ? <span className={`fq-chip ${s.tone}`}>{s.label}</span> : null}
            {f ? <span className="fq-chip">{hidden ? "게릴라 오픈" : "예고 오픈"}</span> : null}
          </div>
          <h2 className="fq-detail-title">{f?.title ?? "…"}</h2>

          {f ? (
            <>
              <div className="fq-fm-stats">
                <div>
                  <b className="fq-num">{f.winnerLimit}</b>
                  <span>당첨 인원</span>
                </div>
                <div>
                  <b className="fq-num">{f.reserveLimit}</b>
                  <span>예비 당첨</span>
                </div>
                <div>
                  <b className="fq-num">{f.feeAmount ? won(f.feeAmount) : "무료"}</b>
                  <span>참가비</span>
                </div>
              </div>

              <ol className="fq-timeline">
                {steps.map((st, i) => {
                  const done = !st.secret && (st.done ?? (st.at !== null && msLeft(st.at, now) <= 0));
                  return (
                    <li key={i} className={done ? "is-done" : ""}>
                      <i aria-hidden="true" />
                      <b>{st.label}</b>
                      {st.secret || !st.at ? <span className="fq-secret">곧 공개</span> : <span className="fq-muted">{dateTime(st.at)}</span>}
                    </li>
                  );
                })}
              </ol>

              {f.status === "OPEN" ? (
                <div className={`fq-clock${msLeft(f.entryClosesAt ?? "", now) > 3 * 60_000 ? " is-calm" : ""}`} style={{ marginTop: 18 }}>
                  <div className="fq-clock-row">
                    <span>응모 마감 시각</span>
                    <span className="fq-num">{f.entryClosesAt ? new Date(f.entryClosesAt).toLocaleTimeString("ko-KR") : "--:--"}</span>
                  </div>
                  <div className="fq-clock-row">
                    <span>마감까지 남은 시간</span>
                    <b className="fq-num">{countdown(f.entryClosesAt ?? "", now)}</b>
                  </div>
                </div>
              ) : null}

              {mine ? (
                <div className="fq-ticket" aria-live="polite">
                  <div>
                    <span className="fq-eyebrow">MY ENTRY</span>
                    <h3>응모 완료</h3>
                    <p>
                      {mine.appliedAt ? `${dateTime(mine.appliedAt)}에 ` : ""}응모했어요.{" "}
                      {f.status === "OPEN" ? "마감 시각 전까지는 취소할 수 있어요." : "결과는 추첨 후 확인할 수 있어요."}
                    </p>
                  </div>
                  <div className="fq-ticket-stub">
                    <span>STATUS</span>
                    <b>{mine.status}</b>
                  </div>
                </div>
              ) : null}

              <div className="fq-cta-box">
                {!profile ? (
                  <Link href={`/login?next=${encodeURIComponent(`/fan-meetings/${id}`)}`} className="fq-btn is-block">
                    로그인하고 응모하기
                  </Link>
                ) : afterDraw ? (
                  <>
                    <button className="fq-btn is-ghost is-block fq-big" disabled>
                      응모 마감
                    </button>
                    <Link href={`/fan-meetings/${f.eventId}/result`} className="fq-btn is-grad is-block" style={{ marginTop: 10 }}>
                      내 추첨 결과 확인하기 →
                    </Link>
                  </>
                ) : mine ? (
                  f.status === "OPEN" ? (
                    <button className="fq-btn is-ghost is-block" onClick={cancel}>
                      응모 취소하기
                    </button>
                  ) : null
                ) : (
                  <button ref={ctaRef} className="fq-btn is-hot is-block fq-big" disabled={f.status !== "OPEN" || busy} onClick={apply}>
                    {f.status === "OPEN"
                      ? busy
                        ? "응모 중…"
                        : `응모하기 (마감 ${countdown(f.entryClosesAt ?? "", now)})`
                      : hidden
                        ? "응모 시작 전이에요"
                        : f.status === "SCHEDULED"
                        ? `응모 시작까지 ${countdown(f.entryOpensAt ?? "", now)}`
                        : "응모 기간이 아니에요"}
                  </button>
                )}
              </div>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
