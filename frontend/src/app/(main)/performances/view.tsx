"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { countdown, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import { checkoutHref } from "@/lib/orders";
import type { TicketBookingAvailability, TicketCheckoutResponse, TicketReservationInfo, TicketSeatInfo, TicketSeatState } from "@/lib/types";
import { isLocalArcSample, mapBounds, sectionLabelPosition, sectionName, sectionOutline, spacedMapSeats } from "./seat-map";
import { mergeSeatStates } from "./seat-state";
import "./seats.css";

const GRADE_COLOR: Record<string, string> = { VIP: "#B04FD0", R: "#6A57F2", S: "#2F8FE0", A: "#1FB89A" };
// 창 포커스로 구매 가능 정보를 다시 조회하는 최소 간격.
const AVAILABILITY_FOCUS_REFRESH_MS = 30_000;

export default function SeatsView({ id, max, concertId }: { id: number; max: number; concertId: string | null }) {
  const now = useNow();
  const toast = useToast();
  const { profile } = useAuth();
  const seats = useLoad(() => profile ? api<TicketSeatInfo[]>(`/api/performances/${id}/seats`) : Promise.resolve([]), [id, profile?.memberId]);
  const seatsRef = useRef(seats);
  seatsRef.current = seats;
  const refreshInFlight = useRef(false);
  const refreshVersion = useRef(0);
  const availability = useLoad(() => profile ? api<TicketBookingAvailability>(`/api/performances/${id}/booking-availability`) : Promise.resolve(null), [id, profile?.memberId]);
  // 창으로 돌아올 때는 마지막 조회 뒤 일정 시간이 지났을 때만 다시 조회한다(#570). 탭을 오갈 때마다 요청하지 않는다.
  // 선점 실패·취소 직후에는 아래에서 바로 다시 조회한다. 선점할 때 서버가 한도와 활성 예약을 다시 검사한다.
  const availabilityLoadedAt = useRef(0);
  useEffect(() => { if (availability.data) availabilityLoadedAt.current = Date.now(); }, [availability.data]);
  const [picked, setPicked] = useState<number[]>([]);
  const [hold, setHold] = useState<TicketReservationInfo | null>(null);
  const [checkout, setCheckout] = useState<TicketCheckoutResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [pollError, setPollError] = useState(false);
  const [refreshingSeats, setRefreshingSeats] = useState(false);
  const [reserveRetry, setReserveRetry] = useState<{ key: string; seatIds: number[] } | null>(null);
  const ctaRef = useRef<HTMLButtonElement>(null);
  const hallRef = useRef<HTMLDivElement>(null);
  const [focusedSection, setFocusedSection] = useState<string | null>(null);

  const list = seats.data ?? [];
  const active = availability.data?.activeReservation ?? null;
  const remaining = availability.data?.remaining ?? 0;
  const selectionBlocked = !availability.data || !!active || !!hold || pollError;
  const sampleLayout = isLocalArcSample(list);
  const displayedSeats = useMemo(() => spacedMapSeats(list), [list]);
  const sections = useMemo(() => {
    const grouped = new Map<string, TicketSeatInfo[]>();
    displayedSeats.forEach((s) => grouped.set(s.sectionLabel, [...(grouped.get(s.sectionLabel) ?? []), s]));
    return [...grouped.entries()].map(([name, items]) => ({ name, items, outline: sectionOutline(items, sampleLayout), label: sectionLabelPosition(items, sampleLayout) }));
  }, [displayedSeats, sampleLayout]);
  const activeSection = sections.find((s) => s.name === focusedSection);
  const bounds = mapBounds(activeSection?.items ?? displayedSeats, 64);
  const seatSize = activeSection ? 16 : 10;
  const focusSection = (name: string | null) => {
    setFocusedSection(name);
    hallRef.current?.scrollIntoView({ block: "start", behavior: "instant" });
  };
  const grades = useMemo(() => {
    const m = new Map<string, number>();
    list.forEach((s) => m.set(s.grade, s.price));
    return [...m.entries()];
  }, [list]);
  const displayedSeatIds = !hold && active ? active.seats.map((s) => s.seatId) : picked;
  const pickedSeats = list.filter((s) => displayedSeatIds.includes(s.seatId));
  const total = pickedSeats.reduce((a, s) => a + s.price, 0);
  const holdLeft = hold ? msLeft(hold.holdExpiresAt, now) : 0;

  const refreshSeatStates = async () => {
    if (refreshInFlight.current || seatsRef.current.data === null || seatsRef.current.loading) return;
    const version = refreshVersion.current;
    refreshInFlight.current = true;
    setRefreshingSeats(true);
    try {
      const states = await api<TicketSeatState[]>(`/api/performances/${id}/seats/status`);
      if (version !== refreshVersion.current) return;
      const current = seatsRef.current;
      if (!current.loading && current.data !== null) {
        const updated = mergeSeatStates(current.data, states);
        if (updated) current.setData(updated);
        else await current.reload();
        if (version === refreshVersion.current) setPollError(false);
      }
    } catch {
      if (version === refreshVersion.current) setPollError(true);
    } finally {
      refreshInFlight.current = false;
      if (version === refreshVersion.current) setRefreshingSeats(false);
    }
  };

  useEffect(() => {
    if (!profile) return;
    refreshVersion.current += 1;
    setPollError(false);
    setRefreshingSeats(false);
    const refreshAvailability = () => {
      if (Date.now() - availabilityLoadedAt.current < AVAILABILITY_FOCUS_REFRESH_MS) return;
      void availability.reload().catch(() => undefined);
    };
    window.addEventListener("focus", refreshAvailability);
    return () => { refreshVersion.current += 1; window.removeEventListener("focus", refreshAvailability); };
  }, [profile?.memberId, id]);

  const toggle = (s: TicketSeatInfo) => {
    if (selectionBlocked || (s.status !== "AVAILABLE" && !picked.includes(s.seatId))) return;
    if (reserveRetry) setReserveRetry(null);
    if (!picked.includes(s.seatId) && picked.length >= remaining) {
      toast(`추가로 ${remaining}매까지 선택할 수 있어요`, "err");
      return;
    }
    setPicked((p) => {
      if (p.includes(s.seatId)) return p.filter((x) => x !== s.seatId);
      if (p.length >= remaining) return p;
      return [...p, s.seatId];
    });
  };

  const reserve = async (retry?: { key: string; seatIds: number[] }) => {
    if (!availability.data || active || pollError || picked.length > remaining) return;
    const request = retry ?? { key: crypto.randomUUID(), seatIds: [...picked] };
    setBusy(true);
    try {
      const data = await api<TicketReservationInfo>("/api/ticket-reservations", { method: "POST", idempotent: request.key, body: { performanceId: id, seatIds: request.seatIds } });
      setHold(data);
            setReserveRetry(null);
      burstFrom(ctaRef.current);
      toast("좌석을 선점했어요. 제한 시간 안에 결제해 주세요.", "ok");
    } catch (e) {
      const err = e as ApiError;
      toast(err.code === "SEAT_UNAVAILABLE" ? "이미 선점된 좌석이 있어요. 다른 좌석을 골라 주세요." : err.message, "err");
      if (err.code === "SEAT_UNAVAILABLE") { setPicked([]); setPollError(true); }
      else if (err.isUnavailable) setReserveRetry(request);
      if (!err.isUnavailable) void availability.reload().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  };

  const doCheckout = async () => {
    const reservationId = hold?.reservationId ?? active?.reservationId;
    if (!reservationId) return;
    setBusy(true);
    try {
      const data = await api<TicketCheckoutResponse>(`/api/ticket-reservations/${reservationId}/checkout`, { method: "POST" });
      setCheckout(data);
            burstFrom(ctaRef.current);
    } catch (e) {
      toast((e as ApiError).message, "err");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    const reservationId = hold?.reservationId ?? active?.reservationId;
    if (!reservationId) return;
    try {
      await api(`/api/ticket-reservations/${reservationId}`, { method: "DELETE" });
    } catch (e) {
      toast((e as ApiError).message, "err");
      return;
    }
    setHold(null);
    setCheckout(null);
    setPicked([]);
    setPollError(true);
    await availability.reload().catch(() => undefined);
    toast("선점을 취소했어요");
  };

  return (
    <div className="fq-seats">
      <Link href={concertId ? `/concerts/detail?id=${concertId}` : "/concerts"} className="fq-back">
        ← 회차 선택
      </Link>
      <div className="fq-seats-grid">
        <div ref={hallRef} className="fq-card fq-hall">
          <div className="fq-between fq-map-heading"><div><h2 className="fq-sub">{activeSection ? `${sectionName(activeSection.name)} 확대 보기` : "좌석 선택"}</h2><p className="fq-muted">{activeSection ? "무대는 위쪽이에요, 선택한 좌석은 구역을 바꿔도 유지돼요" : "구역을 고르고, 마음에 드는 좌석을 눌러 주세요"}</p></div><button type="button" className="fq-btn is-ghost fq-map-reset" disabled={!activeSection} onClick={() => focusSection(null)}>전체 보기 ↗</button></div>
          {!!sections.length && <div className="fq-map-meta"><span>전체 <b>{sections.length}개 구역</b>, {list.length}석</span><span>{activeSection ? `${sectionName(activeSection.name)}, ${activeSection.items.length}석` : "구역 이름을 눌러 확대할 수 있어요"}</span></div>}
          {pollError && <p role="alert" className="fq-note">좌석 상태를 다시 확인해 주세요. 좌석 배치도의 새로고침 버튼을 눌러 주세요.</p>}
          {sampleLayout && <p className="fq-sample-notice"><b>개발용 가상 배치</b> 올림픽홀을 참고한 반원형 샘플이에요. 실제 공식 구역, 좌석번호, 수용 인원, 시야와는 달라요.</p>}
          <div className="fq-stage">STAGE</div>
            <div className={`fq-hall-map ${activeSection ? "is-zoomed" : "is-overview"}`} role="group" aria-label="좌석 배치도">
            {!profile ? <p className="fq-map-empty fq-muted">로그인하면 좌석 배치와 예매 가능한 좌석을 확인할 수 있어요.</p> : seats.error ? <div className="fq-map-empty" role="alert"><p>좌석을 불러오지 못했어요.</p><button type="button" className="fq-btn is-ghost" onClick={() => { void seats.reload().catch(() => undefined); }}>다시 불러오기</button></div> : !list.length ? <p className="fq-map-empty fq-muted">{seats.loading ? "좌석을 불러오는 중이에요…" : "아직 판매 좌석이 없어요."}</p> : <svg role="group" className="fq-seat-svg" viewBox={`${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`} aria-label={activeSection ? `${sectionName(activeSection.name)} 좌석` : "전체 구역 좌석"}>
            {sections.map((section) => <g key={section.name} className={activeSection && activeSection.name !== section.name ? "fq-section-dim" : ""}>
              <path d={section.outline} className={`fq-section-outline${activeSection?.name === section.name ? " is-active" : ""}`} role="button" aria-label={`${sectionName(section.name)} 확대`} tabIndex={activeSection && activeSection.name !== section.name ? -1 : 0} onClick={() => focusSection(section.name)} onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); focusSection(section.name); } }} />
              <text x={section.label.x} y={section.label.y} className="fq-map-section-name">{sectionName(section.name)}</text>
              {(activeSection ? activeSection.name === section.name : section.name === sections[0]?.name) && [...new Set(section.items.map((s) => s.rowLabel))].map((row) => {
                const first = section.items.filter((s) => s.rowLabel === row).reduce((a, b) => Number(a.posX) < Number(b.posX) ? a : b);
                return <text key={row} x={Number(first.posX) - 17} y={Number(first.posY) + 3} className="fq-map-row">{row}열</text>;
              })}
            </g>)}
            {displayedSeats.map((s) => {
              const mine = picked.includes(s.seatId);
              const off = s.status !== "AVAILABLE" && !mine;
              const dimmed = !!activeSection && activeSection.name !== s.sectionLabel;
              return (
                <foreignObject key={s.seatId} x={Number(s.posX) - seatSize / 2} y={Number(s.posY) - seatSize / 2} width={seatSize} height={seatSize} className={dimmed ? "fq-section-dim" : undefined}>
                <button
                  type="button"
                  className={`fq-seat${mine ? " is-mine" : ""}${off ? " is-off" : ""}${hold && mine ? " is-held" : ""}`}
                  style={{ "--g": GRADE_COLOR[s.grade] ?? "#6A57F2" } as React.CSSProperties}
                  disabled={off || selectionBlocked || dimmed}
                  aria-pressed={mine}
                  aria-label={`${sectionName(s.sectionLabel)} ${s.rowLabel}열 ${s.seatNumber}번, ${s.grade}석 ${won(s.price)}${off ? ", 선택 불가" : ""}`}
                  title={`${sectionName(s.sectionLabel)} ${s.rowLabel}열 ${s.seatNumber}번, ${s.grade}석 ${won(s.price)}`}
                  onClick={() => toggle(s)}
                >{s.seatNumber}</button>
                </foreignObject>
              );
            })}
            </svg>}
            <button type="button" className="fq-seat-refresh" aria-label={refreshingSeats ? "좌석 상태 갱신 중" : "좌석 상태 새로고침"} title={refreshingSeats ? "갱신 중…" : "좌석 상태 새로고침"} disabled={!profile || seats.data === null || seats.loading || refreshingSeats} onClick={() => { void refreshSeatStates(); }}>
              <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 7v5h-5"/><path d="M19 12a7 7 0 1 0-2.1 5"/></svg>
            </button>
          </div>
          {!!sections.length && <section className="fq-section-picker" aria-label="구역 선택"><div className="fq-between"><h3 className="fq-sub">구역 둘러보기</h3><span className="fq-muted">클릭하면 강조, 확대돼요</span></div><div className="fq-section-options">{sections.map((section) => {
            const available = section.items.filter((s) => s.status === "AVAILABLE").length;
            const prices = section.items.map((s) => s.price);
            const current = activeSection?.name === section.name;
            return <button type="button" key={section.name} className={`fq-section-option${current ? " is-current" : ""}`} aria-pressed={current} onClick={() => focusSection(section.name)}><span className="fq-between"><strong>{sectionName(section.name)}</strong><span aria-hidden="true">{current ? "✓" : "↗"}</span></span><span>{current ? "보고 있는 구역, " : ""}총 {section.items.length}석, 가능 {available}석</span><small>{won(Math.min(...prices))}{Math.min(...prices) !== Math.max(...prices) ? `~${won(Math.max(...prices))}` : ""}</small></button>;
          })}</div><p className="fq-map-caption">좌석 선택만으로는 선점되지 않아요. ‘좌석 선점’을 눌러야 선점돼요. 잔여석은 주기적으로 갱신되며 선점 시 다시 확인해요.</p></section>}
          <div className="fq-legend">
            {grades.map(([g, price]) => (
              <span key={g}>
                <i style={{ background: GRADE_COLOR[g] ?? "#6A57F2" }} />
                {g}석 {won(price)}
              </span>
            ))}
            <span>
              <i className="is-off" />
              선택 불가
            </span>
          </div>
        </div>

        <aside className="fq-card is-pad fq-cart">
          {!checkout && active && !hold ? (
            <div className="fq-done">
              <h3>이 회차에 진행 중인 예약이 있어요</h3>
              <p className="fq-muted">예약번호 #{active.reservationId}, {active.seats.length}석</p>
              {active.status === "HELD" ? <>
                <p>선점 남은 시간: {countdown(active.holdExpiresAt, now)}</p>
                <button className="fq-btn is-grad is-block" disabled={busy || active.holdExpired || msLeft(active.holdExpiresAt, now) <= 0} onClick={doCheckout}>결제 이어가기</button>
                <button className="fq-btn is-ghost is-block" disabled={busy} onClick={cancel}>선점 취소</button>
              </> : <>
                <p className="fq-muted">{active.status === "RECONCILING" ? "결제 결과를 확인하고 있어요." : "결제가 진행 중이에요."} 결제 진행 후에는 선점 취소를 할 수 없어요.</p>
                {active.orderId && <Link className="fq-btn is-grad is-block" href={`/checkout?order=${active.orderId}`}>주문, 결제 확인</Link>}
              </>}
            </div>
          ) : checkout ? (
            <div className="fq-done">
              <div className="fq-done-ico">Q</div>
              <h3>결제를 진행해 주세요</h3>
              <p className="fq-muted">주문번호 #{checkout.orderId}</p>
              <dl className="fq-kv">
                <dt>결제 금액</dt>
                <dd className="fq-num">{won(checkout.totalAmount)}</dd>
                <dt>결제 기한</dt>
                <dd className="fq-num">{countdown(checkout.paymentExpiresAt, now)}</dd>
              </dl>
              <Link
                className="fq-btn is-grad is-block"
                href={checkoutHref({
                  orderId: checkout.orderId,
                  kind: "TICKET",
                  title: `공연 좌석 ${pickedSeats.length}석 (${pickedSeats.map((s) => `${s.sectionLabel}${s.rowLabel}-${s.seatNumber}`).join(", ")})`,
                  amount: checkout.totalAmount,
                  paymentExpiresAt: checkout.paymentExpiresAt,
                })}
              >
                결제하기
              </Link>
              <p className="fq-muted">결제 진입 후에는 선점 취소를 할 수 없어요.</p>
            </div>
          ) : (
            <>
              <div className="fq-between">
                <h3 className="fq-sub" style={{ margin: 0 }}>
                  선택한 좌석
                </h3>
                <span className="fq-chip">
                  {picked.length} / {availability.data?.remaining ?? "-"}
                </span>
              </div>
              {profile && <p className="fq-muted">{availability.error ? "구매 한도를 불러오지 못했어요." : availability.data ? `회차당 ${availability.data.maximum}매, 구매 확정 ${availability.data.confirmed}매, 추가 가능 ${availability.data.remaining}매` : "구매 한도를 확인하고 있어요…"}</p>}
              {availability.error && <button className="fq-btn is-ghost is-block" onClick={() => { void availability.reload().catch(() => undefined); }}>다시 확인</button>}
              {hold ? (
                <div className={`fq-hold${holdLeft < 60_000 ? " is-urgent" : ""}`}>
                  <span>좌석 선점 남은 시간</span>
                  <b className="fq-num">{countdown(hold.holdExpiresAt, now)}</b>
                </div>
              ) : null}
              <ul className="fq-picked">
                {pickedSeats.length === 0 ? <li className="fq-muted">배치도에서 좌석을 눌러 골라 주세요</li> : null}
                {pickedSeats.map((s) => (
                  <li key={s.seatId}>
                    <i style={{ background: GRADE_COLOR[s.grade] ?? "#6A57F2" }} />
                    <span>
                      {sectionName(s.sectionLabel)} {s.rowLabel}열 {s.seatNumber}번 <small>{s.grade}석</small>
                    </span>
                    <b className="fq-num">{won(s.price)}</b>
                  </li>
                ))}
              </ul>
              <div className="fq-between fq-total">
                <span>합계</span>
                <b className="fq-num">{won(total)}</b>
              </div>
              {!profile ? (
                <Link href={`/login?next=${encodeURIComponent(`/performances?id=${id}&max=${max}&concert=${concertId ?? ""}`)}`} className="fq-btn is-block">
                  로그인하고 예매하기
                </Link>
              ) : hold ? (
                <>
                  <button ref={ctaRef} className="fq-btn is-grad is-block" disabled={busy || holdLeft <= 0} onClick={doCheckout}>
                    {holdLeft <= 0 ? "선점 시간이 끝났어요" : "결제 진행하기"}
                  </button>
                  <button className="fq-btn is-ghost is-block" onClick={cancel}>
                    선점 취소
                  </button>
                </>
              ) : (
                <button ref={ctaRef} className="fq-btn is-hot is-block" disabled={!availability.data || !!active || pollError || picked.length > remaining || (!picked.length && !reserveRetry) || busy} onClick={() => reserve(reserveRetry ?? undefined)}>
                  {busy ? "선점 중…" : reserveRetry ? "선점 결과 다시 확인" : "좌석 선점"}
                </button>
              )}
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
