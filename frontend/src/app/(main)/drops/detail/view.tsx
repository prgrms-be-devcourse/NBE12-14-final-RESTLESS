"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useEffectEvent, useRef, useState } from "react";
import { DropStatusChip, firstWord } from "@/components/Cards";
import Poster from "@/components/Poster";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { IntroGate } from "@/lib/intro";
import { closeTossPayment, openTossPayment } from "@/lib/toss";
import { countdown, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import LoadError from "@/components/LoadError";
import { checkoutHref, isPaymentDeadlineError } from "@/lib/orders";
import type { DropDetail, DropReservationCreated, DropReservationDetail, OrderDetail, OrderPage, PreparedPayment, QueueView } from "@/lib/types";
import type { StickColor } from "@/components/lightstick/Lightstick";
import { DropTimeline, QueueGauge, SpecSheet } from "./parts";
import {
  ACTIVE_ORDER_PAGE_SIZE,
  ADDRESS_LIMIT,
  EMPTY_ADDRESS,
  ENTRY_BUSY_RETRIES,
  RESERVE_AUTO_RETRIES,
  findActiveDropOrder,
  activeOrderAction,
  addressProblem,
  clearPurchase,
  defaultAddress,
  entryFailure,
  loadPurchase,
  pollDelay,
  queueAction,
  reserveFailure,
  reserveRetryDelay,
  restoreFor,
  retryDelay,
  savePurchase,
  trimAddress,
  type Address,
  type DoneReservation,
  type PendingReservation,
  type SavedPurchase,
} from "./purchase";
import "./drop.css";

// three.js는 무겁다. 응원봉 드롭에서만, 브라우저에서만 따로 불러온다
const Lightstick = dynamic(() => import("@/components/lightstick/Lightstick"), { ssr: false });
const STICK_SKU = /^LIGHTSTICK-(BLACK|WHITE|SILVER)$/;
const stickColor = (sku?: string) => (sku?.match(STICK_SKU)?.[1] as StickColor | undefined);

type Step = "idle" | "waiting" | "admitted" | "consumed";
const STEPS = ["옵션과 배송지", "대기열", "결제"];

function AddressFields({ value, onChange, onLoadDefault, hasDefault, disabled }: {
  value: Address;
  onChange: (patch: Partial<Address>) => void;
  onLoadDefault: () => void;
  hasDefault: boolean;
  disabled?: boolean;
}) {
  return (
    <fieldset className="fq-addr" disabled={disabled} style={{ border: 0, padding: 0, margin: "12px 0", display: "grid", gap: 8 }}>
      <div className="fq-between">
        <legend className="fq-eyebrow" style={{ padding: 0 }}>배송지</legend>
        {hasDefault ? (
          <button type="button" className="fq-link" onClick={onLoadDefault}>기본 배송지로 되돌리기</button>
        ) : null}
      </div>
      {!hasDefault ? <p className="fq-muted fq-note" style={{ margin: 0 }}>등록된 기본 배송지가 없어요. 이번 주문의 배송지를 입력해 주세요.</p> : null}
      <div className="fq-form-2">
        <input className="fq-input" placeholder="받는 분" aria-label="받는 분" maxLength={ADDRESS_LIMIT.recipient} value={value.recipient} onChange={(e) => onChange({ recipient: e.target.value })} />
        <input className="fq-input" placeholder="연락처 010-0000-0000" aria-label="연락처" inputMode="tel" maxLength={ADDRESS_LIMIT.phone} value={value.phone} onChange={(e) => onChange({ phone: e.target.value })} />
      </div>
      <input className="fq-input" placeholder="주소" aria-label="주소" maxLength={ADDRESS_LIMIT.address} value={value.address} onChange={(e) => onChange({ address: e.target.value })} />
    </fieldset>
  );
}

function AddressSummary({ value }: { value: Address }) {
  return (
    <p style={{ margin: "4px 0 0" }}>
      <b>{value.recipient}</b> <span className="fq-num">{value.phone}</span>
      <br />
      <span className="fq-muted">{value.address}</span>
    </p>
  );
}

type ActiveOrder = { status: string; rec: DoneReservation; shipping: Address | null };

export default function DropView({ id }: { id: number }) {
  const { profile } = useAuth();
  // 회원이 바뀌면(로그아웃, 다른 계정 로그인) 화면을 통째로 새로 만든다. 이전 회원의 요청은 active 검사로 버려진다
  return <DropPurchaseView key={`${id}-${profile?.memberId ?? "guest"}`} id={id} />;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

const MSG = {
  unavailable: "기다리는 동안 선택한 옵션을 지금 구매할 수 없게 됐어요(현재 구매 불가). 재고가 돌아오면 다시 결제하기를 누를 수 있어요.",
  admissionExpired: "입장 허가 시간이 지났어요. 결제하기를 다시 눌러 대기열에 들어가 주세요.",
  notOnSale: "지금은 판매 시간이 아니에요.",
} as const;

function DropPurchaseView({ id }: { id: number }) {
  const now = useNow();
  const toast = useToast();
  const router = useRouter();
  const { profile } = useAuth();
  const memberId = profile?.memberId ?? null;
  const { data: d, error, loading, setData } = useLoad(() => api<DropDetail>(`/api/drops/${id}`), [id]);
  const [step, setStep] = useState<Step>("idle");
  const [queue, setQueue] = useState<QueueView | null>(null);
  const [startPos, setStartPos] = useState(1);
  const [variantId, setVariantId] = useState<number | null>(null);
  // 입력 중인 배송지
  const [form, setForm] = useState<Address>(EMPTY_ADDRESS);
  // 이번 입장에 쓰기로 확인한 배송지. 결제하기를 누른 뒤에는 이 값과 SKU를 고정한다
  const [confirmed, setConfirmed] = useState<Address | null>(null);
  const [retryReserve, setRetryReserve] = useState(false);
  // 결제하기를 누를 때 만든 재고 점유 요청. 응답을 못 받아도 같은 키와 body로만 다시 보낸다
  const [pending, setPending] = useState<PendingReservation | null>(null);
  const [done, setDone] = useState<DoneReservation | null>(null);
  // 새로고침으로 되찾은 주문의 서버 상태. RESERVED가 아니면 결제창을 다시 열지 않는다
  const [doneStatus, setDoneStatus] = useState<string>("RESERVED");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const inflight = useRef(false);
  const dirty = useRef(false);
  const entered = useRef(false);
  const active = useRef(false);
  const ownsToss = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      // 이 화면이 연 결제창이 화면을 떠난 뒤에 남아 있지 않게 닫는다
      if (ownsToss.current) void closeTossPayment();
      ownsToss.current = false;
    };
  }, []);

  const open = d ? ["ON_SALE", "OPEN"].includes(d.saleStatus) && msLeft(d.closesAt, now) > 0 && msLeft(d.opensAt, now) <= 0 : false;
  const variant = d?.variants.find((v) => v.variantId === variantId);
  const hasDefault = !!(profile?.defaultRecipient || profile?.defaultPhone || profile?.defaultAddress);

  // 회원 기본 배송지를 미리 채운다. 사용자가 고친 값은 profile이 다시 와도 덮지 않는다.
  useEffect(() => {
    if (!dirty.current) setForm(defaultAddress(profile));
  }, [memberId, profile]);

  const editForm = (patch: Partial<Address>) => {
    dirty.current = true;
    setForm((f) => ({ ...f, ...patch }));
  };
  const loadDefault = () => {
    dirty.current = true;
    setForm(defaultAddress(profile));
  };

  /** 품절 표시를 새로 받는다. 화면을 스켈레톤으로 되돌리지 않게 조용히 바꾼다 */
  const refreshDrop = () => {
    api<DropDetail>(`/api/drops/${id}`).then((next) => { if (active.current) setData(next); }).catch(() => undefined);
  };

  const persist = (vid: number, patch: Partial<SavedPurchase> & { address?: Address }) => {
    if (memberId == null) return;
    const cur = loadPurchase(memberId, id);
    const base = cur && cur.variantId === vid ? cur : null;
    const address = patch.address ?? base?.address ?? confirmed;
    if (!address) return;
    savePurchase({ v: 1, memberId, dropId: id, variantId: vid, pending: null, done: null, ...base, ...patch, address });
  };

  // 새로고침 복구. 서버가 돌려준 대기의 SKU와 지금 회원, 드롭이 저장본과 맞을 때만 확인한 배송지와 요청 키를 다시 쓴다.
  useEffect(() => {
    if (memberId == null || !d) return;
    let cancelled = false;
    const stale = () => cancelled || entered.current || !active.current;
    (async () => {
      let view: QueueView;
      try {
        view = await api<QueueView>(`/api/drops/${id}/queue/me`);
      } catch {
        // 대기 기록이 없다. 이 탭이 이어가던 주문이 있으면 그 주문이 아직 진행 중인지 확인하고, 아니면 처음부터 고른다
        if (!loadPurchase(memberId, id)?.done) return;
        const found = await findActiveOrder();
        if (!stale() && found) showActiveOrder(found, null);
        return; // 확인하지 못했으면 선택 화면에서 주문 확인 다시 시도를 보여 준다
      }
      if (stale()) return;
      const saved = restoreFor(loadPurchase(memberId, id), memberId, id, view.variantId);
      if (view.variantId) setVariantId(view.variantId);
      if (view.status === "WAITING" || view.status === "ADMITTED") {
        if (!saved) return; // 상세에서 SKU와 배송지를 다시 확인한 후 결제하기를 누르면 기존 순번으로 이어간다.
        setQueue(view);
        setStartPos(Math.max(1, view.position ?? 1));
        dirty.current = true;
        setForm(saved.address);
        setConfirmed(saved.address);
        setPending(saved.pending ?? null);
        setRetryReserve(false);
        setStep(view.status === "WAITING" ? "waiting" : "admitted");
      } else if (view.status === "CONSUMED") {
        setQueue(view);
        if (saved?.pending) {
          // 점유 요청의 응답을 못 받은 채 새로고침했다. 같은 요청으로 결과를 다시 확인한다
          dirty.current = true;
          setForm(saved.address);
          setConfirmed(saved.address);
          setPending(saved.pending);
          setStep("admitted");
          return;
        }
        if (!saved?.done) {
          // 다른 탭이나 기기에서 만든 주문일 수 있다. 진행 중 주문을 찾아 그 주문으로 이어간다 (선택지 A). 결제창은 누를 때 연다
          const found = await findActiveOrder();
          if (stale()) return;
          if (found) return showActiveOrder(found, null);
          // 진행 중 주문이 없으면 지난 구매는 끝났으니 처음부터 고른다.
          // 확인하지 못했으면 선택 화면에서 주문 확인 다시 시도를 보여 준다
          return;
        }
        let rec: DoneReservation | null = saved.done;
        let status = "RESERVED";
        if (rec) {
          // 저장본의 주문이 아직 결제 대기인지 서버에 묻는다. 결제했거나 만료된 주문을 결제 대기로 보이지 않는다
          try {
            const r = await api<DropReservationDetail>(`/api/drops/${id}/reservations/${rec.reservationId}`);
            if (r.variantId !== rec.variantId || r.orderId !== rec.orderId) rec = null;
            else {
              status = r.status;
              rec = { ...rec, amount: r.amount, expiresAt: r.expiresAt };
            }
          } catch (e) {
            if (e instanceof ApiError && e.status === 404) rec = null;
          }
          if (stale()) return;
          if (!rec && saved) savePurchase({ ...saved, done: null });
        }
        setDone(rec);
        setDoneStatus(status);
        setStep("consumed");
      }
    })();
    return () => { cancelled = true; };
  }, [id, memberId, d?.dropId]);

  /** 대기를 그만두고 옵션 고르기로 돌아간다. 보내지 않은 요청 키는 버린다 */
  const leaveToIdle = (msg: string) => {
    if (variantId != null) persist(variantId, { pending: null });
    setPending(null);
    setConfirmed(null);
    setQueue(null);
    setStep("idle");
    toast(msg, "err");
  };
  const leaveQueue = useEffectEvent(leaveToIdle);
  const refreshDropEvent = useEffectEvent(refreshDrop);

  // 대기 중이면 서버에서 순번을 계속 확인한다. 상태 조회는 heartbeat도 겸한다(40초 끊기면 이탈).
  // 간격은 서버가 준 pollAfterMs를 따르고 그보다 자주 묻지 않는다. 429, 503은 Retry-After에 지터를 더해 기다린다.
  const queueRef = useRef<QueueView | null>(null);
  useEffect(() => { queueRef.current = queue; }, [queue]);
  useEffect(() => {
    if (step !== "waiting" || variantId == null) return;
    const locked = variantId;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      let delay: number;
      try {
        const view = await api<QueueView>(`/api/drops/${id}/queue/me`);
        if (stopped) return;
        const action = queueAction(view, locked);
        if (action === "otherSku") return leaveQueue("다른 창에서 다른 옵션으로 대기열에 들어가 이 창의 대기를 멈췄어요. 옵션과 배송지를 확인하고 다시 결제하기를 눌러 주세요.");
        setQueue(view);
        if (action === "reserve") { setStep("admitted"); return; }
        if (action === "consumed") { setStep("consumed"); return; }
        if (action === "expired") return leaveQueue(MSG.admissionExpired);
        if (action === "unavailable") { refreshDropEvent(); return leaveQueue(MSG.unavailable); }
        delay = pollDelay(view);
      } catch (e) {
        if (stopped) return;
        const err = e instanceof ApiError ? e : null;
        if (err?.status === 404) return leaveQueue("오래 응답이 없어 대기열에서 빠졌어요. 결제하기를 다시 누르면 맨 뒤에서 다시 기다려요.");
        if (err && (err.code === "DROP_QUEUE_BUSY" || err.status === 429)) delay = retryDelay(err.retryAfterMs, 1000);
        else delay = pollDelay(queueRef.current);
      }
      if (!stopped) timer = setTimeout(tick, delay);
    };
    timer = setTimeout(tick, pollDelay(queueRef.current));
    return () => { stopped = true; clearTimeout(timer); };
  }, [step, id, variantId]);

  /**
   * 이 드롭의 진행 중 주문(결제 대기, 승인 중, 결과 확인 중)을 찾는다. 판매 중에는 재고 점유가 409 DROP_ACTIVE_PURCHASE_EXISTS일 때만 부른다 (#453).
   * 새로고침 복구에서 저장본이나 CONSUMED 대기가 있을 때도 부른다.
   * 목록에는 dropId가 없어 주문 상세의 payable(reservationId)로 이 드롭의 재고 점유를 조회해 가린다. 다른 드롭이면 404다.
   * 없으면 null, 확인하지 못하면 undefined다.
   */
  const findActiveOrder = async (): Promise<ActiveOrder | null | undefined> => {
    try {
      const found = await findActiveDropOrder({
        listPage: (page) => api<OrderPage>(`/api/me/orders?page=${page}&size=${ACTIVE_ORDER_PAGE_SIZE}`),
        detail: (orderId) => api<OrderDetail>(`/api/orders/${orderId}`),
        reservation: (reservationId) => api<DropReservationDetail>(`/api/drops/${id}/reservations/${reservationId}`),
      });
      if (!found) return null;
      const r = found.reservation;
      // 이 주문의 결제 준비 키가 저장돼 있으면 그대로 써서 같은 결제 시도를 받는다
      const saved = memberId == null ? null : loadPurchase(memberId, id);
      const prepareKey = saved?.done?.reservationId === r.reservationId ? saved.done.prepareKey : undefined;
      return {
        status: found.status,
        shipping: found.shipping,
        rec: { reservationId: r.reservationId, orderId: r.orderId, amount: r.amount, expiresAt: r.expiresAt, variantId: r.variantId, prepareKey: prepareKey ?? crypto.randomUUID() },
      };
    } catch {
      // 기존 주문 유무를 모른다. 부른 쪽이 안내를 정한다
      return undefined;
    }
  };

  /** 찾은 진행 중 주문을 화면에 놓는다. 저장본도 이 주문으로 맞춰 새로고침 뒤 같은 결제 준비 키를 쓴다 */
  const showActiveOrder = (found: ActiveOrder, fallback: Address | null) => {
    const { rec } = found;
    const shipped = found.shipping ?? fallback;
    if (memberId != null && shipped) savePurchase({ v: 1, memberId, dropId: id, variantId: rec.variantId, address: shipped, pending: null, done: rec });
    setVariantId(rec.variantId);
    setConfirmed(shipped);
    setPending(null);
    setRetryReserve(false);
    setQueue(null);
    setDone(rec);
    setDoneStatus(activeOrderAction(found.status) === "pay" ? "RESERVED" : "CHECKING");
    setStep("consumed");
  };

  /**
   * 결제하기: SKU, 배송지, 재고 점유용 Idempotency-Key를 여기서 확정하고 선택한 SKU의 대기열에 입장 신청한다.
   * 대기열 API에는 variantId만 보낸다. 503 DROP_QUEUE_BUSY는 Retry-After 뒤 몇 번 자동으로 다시 보낸다.
   */
  const enter = async () => {
    if (inflight.current || memberId == null) return;
    if (!variantId || !variant || variant.soldOut) return toast("구매할 옵션을 골라 주세요.", "err");
    const problem = addressProblem(form);
    if (problem) return toast(problem, "err");
    const vid = variantId;
    const address = trimAddress(form);
    const request: PendingReservation = { key: crypto.randomUUID(), body: { variantId: vid, ...address } };
    const previous = loadPurchase(memberId, id);
    const rollback = () => (previous ? savePurchase(previous) : clearPurchase(memberId, id));
    entered.current = true;
    inflight.current = true;
    setBusy(true);
    // 진행 중 주문은 미리 조회하지 않는다. 판매가 열리는 순간 모든 구매자가 주문 목록을 부르지 않게 한다.
    // 그런 주문이 있으면 재고 점유가 409 DROP_ACTIVE_PURCHASE_EXISTS를 받고, 그때 찾아 이어간다 (#453)
    // 응답을 못 받고 새로고침해도 같은 SKU의 대기라면 확인한 배송지와 키를 이어 쓸 수 있게 먼저 저장한다
    savePurchase({ v: 1, memberId, dropId: id, variantId: vid, address, pending: request, done: null });
    let data: QueueView | null = null;
    let failure: ApiError | null = null;
    for (let attempt = 0; active.current; attempt++) {
      try {
        data = await api<QueueView>(`/api/drops/${id}/queue/entries`, { method: "POST", body: { variantId: vid } });
        break;
      } catch (e) {
        const err = e instanceof ApiError ? e : new ApiError(0, { code: "NETWORK", message: (e as Error).message });
        if (entryFailure(err) !== "busy" || attempt >= ENTRY_BUSY_RETRIES) { failure = err; break; }
        const wait = retryDelay(err.retryAfterMs, 5000);
        setNotice(`대기열 요청이 몰려 있어요. ${Math.ceil(wait / 1000)}초 뒤 자동으로 다시 시도해요.`);
        await sleep(wait);
      }
    }
    // 자동 재고 점유가 이 잠금에 막히지 않게 화면 상태를 바꾸기 전에 푼다
    inflight.current = false;
    if (!active.current) return;
    setBusy(false);
    setNotice(null);

    if (!data || (data.variantId != null && data.variantId !== vid) || (data.status !== "WAITING" && data.status !== "ADMITTED")) {
      rollback();
      if (data) return toast("대기열 상태가 선택한 옵션과 달라요. 결제하기를 다시 눌러 주세요.", "err");
      switch (entryFailure(failure)) {
        case "busy": return toast("대기열 요청이 몰려 있어요. 잠시 뒤 결제하기를 다시 눌러 주세요. 선택과 배송지는 그대로예요.", "err");
        case "soldOut":
          refreshDrop();
          return toast("선택한 옵션은 지금 구매할 수 없어요(현재 구매 불가). 다른 옵션을 고르거나 재고가 돌아오면 다시 눌러 주세요.", "err");
        case "notOnSale": return toast(MSG.notOnSale, "err");
        case "rateLimited": return toast("요청이 너무 잦아요. 잠시 뒤 결제하기를 다시 눌러 주세요.", "err");
        case "unknown": return toast("대기열 입장 결과를 확인하지 못했어요. 결제하기를 다시 누르면 같은 대기로 이어져요.", "err");
        default: return toast(failure?.message ?? "대기열에 들어가지 못했어요.", "err");
      }
    }
    setForm(address);
    setConfirmed(address);
    setPending(request);
    setRetryReserve(false);
    setQueue(data);
    setStartPos(Math.max(1, data.position ?? 1));
    setStep(data.status === "ADMITTED" ? "admitted" : "waiting");
  };

  /** 이미 만든 주문의 결제를 준비하고 같은 화면에서 토스 결제창을 연다. 결제 준비에는 reservationId, 토스에는 서버의 pgOrderId와 금액을 쓴다 */
  const startPayment = async (record: DoneReservation) => {
    if (inflight.current) return;
    inflight.current = true;
    setBusy(true);
    let rec = record;
    if (!rec.prepareKey) {
      rec = { ...rec, prepareKey: crypto.randomUUID() };
      persist(rec.variantId, { done: rec });
      setDone(rec);
    }
    try {
      const prepared = await api<PreparedPayment>("/api/payments", {
        method: "POST", idempotent: rec.prepareKey,
        body: { payableType: "DROP", payableId: rec.reservationId },
      });
      if (!active.current) return;
      const hint = { kind: "DROP", title: d?.title ?? "", amount: prepared.amount, expires: prepared.expiresAt ?? rec.expiresAt, back: `/drops/detail?id=${id}` };
      if (prepared.status !== "READY") {
        // 승인 중, 실패, 완료 같은 상태는 공통 결제 화면이 서버 상태로 보여 준다
        router.push(checkoutHref({ orderId: rec.orderId, kind: "DROP", title: hint.title, amount: prepared.amount, paymentExpiresAt: hint.expires, back: hint.back }));
        return;
      }
      // 토스 성공, 실패 복귀 화면이 이 드롭으로 돌아오는 길을 알게 한다 (공통 checkout과 같은 키)
      try { sessionStorage.setItem(`checkout-hint-${rec.orderId}`, JSON.stringify(hint)); } catch { /* 힌트 없이도 복귀 화면은 서버 주문을 읽는다 */ }
      const opened = await openTossPayment({ orderId: rec.orderId, pgOrderId: prepared.pgOrderId, amount: prepared.amount, orderName: prepared.orderName }, {
        isCurrent: () => active.current,
        onCancel: () => toast("결제창을 닫았어요. 결제창 다시 열기를 누르면 같은 주문으로 이어서 결제해요.", "info"),
        onError: (e) => toast(`${(e as Error)?.message || "결제를 요청하지 못했어요."} 결제창 다시 열기로 같은 주문을 이어갈 수 있어요.`, "err"),
      });
      if (opened) ownsToss.current = true;
    } catch (e) {
      if (!active.current) return;
      const err = e instanceof ApiError ? e : null;
      if (isPaymentDeadlineError(err)) {
        // 기한 뒤(PAYMENT_WINDOW_EXPIRED)와 기한과 같은 시각(DROP_NOT_PAYABLE)을 같은 기한 경과로 보여 주고 주문 상태를 다시 읽는다
        setDoneStatus("DEADLINE");
        toast("결제 기한이 지났어요. 확보한 재고는 곧 반환돼요.", "err");
        api<DropReservationDetail>(`/api/drops/${id}/reservations/${rec.reservationId}`)
          .then((r) => { if (active.current && r.status !== "RESERVED") setDoneStatus(r.status); })
          .catch(() => undefined);
        return;
      }
      if (err?.code === "PAYMENT_FAILED") {
        // 실패로 닫힌 시도다. 다음 준비는 새 시도로 만든다
        const next = { ...rec, prepareKey: crypto.randomUUID() };
        persist(next.variantId, { done: next });
        setDone(next);
      }
      // 서버 거절은 서버 문구를, SDK 로드 실패 같은 브라우저 오류는 쉬운 문구를 보여 준다
      const reason = err ? err.message : "결제창을 열지 못했어요.";
      toast(`${reason} 결제창 다시 열기로 같은 주문을 이어갈 수 있어요.`, "err");
    } finally {
      inflight.current = false;
      if (active.current) setBusy(false);
    }
  };

  /** 입장 허가를 받으면 재고 점유와 주문 생성을 요청하고, 성공하면 바로 결제창을 연다. */
  const reserve = async () => {
    if (inflight.current) return;
    if (!variantId || !confirmed) return toast("배송지를 확인해 주세요.", "err");
    const request: PendingReservation = pending ?? { key: crypto.randomUUID(), body: { variantId, ...confirmed } };
    // 응답이 끊긴 뒤 새로고침해도 같은 키와 body로 다시 확인할 수 있게 보내기 전에 저장한다
    setPending(request);
    persist(request.body.variantId, { address: confirmed, pending: request });
    inflight.current = true;
    setBusy(true);
    let rec: DoneReservation | null = null;
    let failure: ApiError | null = null;
    // 결과를 모르면 같은 키와 body로 짧게 기다렸다가 몇 번 자동으로 다시 보낸다. 서버는 같은 키의 기존 점유를 그대로 돌려준다
    for (let attempt = 0; ; attempt++) {
      try {
        const data = await api<DropReservationCreated>(`/api/drops/${id}/reservations`, { method: "POST", idempotent: request.key, body: request.body });
        rec = { reservationId: data.reservationId, orderId: data.orderId, amount: data.amount, expiresAt: data.expiresAt, variantId: request.body.variantId, prepareKey: crypto.randomUUID() };
        // 화면을 떠났어도 이 회원의 저장본에는 남겨 둔다. 다시 들어오면 같은 주문에서 이어간다
        persist(request.body.variantId, { pending: null, done: rec });
        break;
      } catch (e) {
        const err = e instanceof ApiError ? e : new ApiError(0, { code: "NETWORK", message: (e as Error)?.message });
        if (reserveFailure(err) !== "unknown" || attempt >= RESERVE_AUTO_RETRIES || !active.current) { failure = err; break; }
        setNotice("주문 결과를 확인하지 못해 같은 요청으로 다시 확인하고 있어요.");
        await sleep(reserveRetryDelay(attempt, err.retryAfterMs));
        // 화면을 떠났으면 멈춘다. 저장한 키와 body로 다시 들어왔을 때 이어서 확인한다
        if (!active.current) { failure = err; break; }
      }
    }
    inflight.current = false;
    if (!active.current) return;
    setBusy(false);
    setNotice(null);
    if (!rec) {
      const err = failure;
      setRetryReserve(true);
      switch (reserveFailure(err)) {
        case "unknown":
          // 결과를 모른다. 실패로 단정하지 않고 같은 요청을 다시 보낼 수 있게 남긴다
          return toast("결과를 확인하지 못했어요. 결과 다시 확인을 눌러 주세요. 같은 요청이라 두 번 주문되지 않아요.", "err");
        case "soldOut":
          refreshDrop();
          return leaveToIdle("선택한 옵션의 재고가 없어 주문하지 못했어요(현재 구매 불가). 다른 옵션을 골라 주세요.");
        case "admissionExpired": return leaveToIdle(MSG.admissionExpired);
        case "notOnSale": return leaveToIdle(MSG.notOnSale);
        case "activeExists": {
          // 이 드롭에 진행 중 주문이 이미 있다. 이때만 주문을 찾아 그 주문으로 이어간다 (#453)
          persist(request.body.variantId, { pending: null });
          setPending(null);
          setRetryReserve(false);
          inflight.current = true;
          setBusy(true);
          const existing = await findActiveOrder();
          inflight.current = false;
          if (!active.current) return;
          setBusy(false);
          if (existing) {
            showActiveOrder(existing, confirmed);
            if (activeOrderAction(existing.status) === "pay") {
              toast("이 드롭에 결제 대기 중인 주문이 있어 그 주문으로 결제를 이어가요.", "info");
              await startPayment(existing.rec);
            } else {
              toast("이 드롭의 주문 결제를 확인하고 있어요. 확인이 끝나기 전에는 새로 구매할 수 없어요.", "info");
            }
            return;
          }
          setDone(null);
          setStep("consumed");
          return toast("이 드롭에 진행 중인 주문이 있어요. 내 주문에서 이어서 결제해 주세요.", "err");
        }
        case "invalid": return leaveToIdle("배송지 정보를 다시 확인해 주세요.");
        default: return toast(err?.message ?? "주문하지 못했어요.", "err");
      }
    }
    setPending(null);
    setDone(rec);
    setDoneStatus("RESERVED");
    setStep("consumed");
    await startPayment(rec);
  };

  const reserveOnAdmission = useEffectEvent(reserve);
  useEffect(() => {
    if (step === "admitted" && !retryReserve && confirmed) {
      void reserveOnAdmission();
    }
  }, [step, confirmed, retryReserve, memberId]);

  const cancelReservation = async () => {
    if (!done || inflight.current) return;
    inflight.current = true;
    setBusy(true);
    try {
      await api(`/api/drops/${id}/reservations/${done.reservationId}/cancel`, { method: "POST" });
      if (!active.current) return;
      if (memberId != null) clearPurchase(memberId, id);
      setDone(null); setQueue(null); setStep("idle"); setConfirmed(null); setPending(null);
      refreshDrop();
      toast("구매를 취소했어요. 다시 사려면 결제하기를 눌러 주세요.", "ok");
    } catch (e) { if (active.current) toast((e as ApiError).message, "err"); }
    finally { inflight.current = false; if (active.current) setBusy(false); }
  };

  /** 결제가 끝났거나 만료된 지난 주문을 접고 처음부터 고른다 */
  const startOver = () => {
    if (memberId != null) clearPurchase(memberId, id);
    setDone(null); setQueue(null); setConfirmed(null); setPending(null); setRetryReserve(false); setDoneStatus("RESERVED");
    setStep("idle");
  };

  const pos = queue?.position ?? 0;
  const pct = step === "waiting" ? Math.min(100, Math.max(3, (1 - pos / startPos) * 100)) : 100;

  if (error) return <LoadError error={error} backHref="/drops" />;
  if (loading || !d) return <div className="fq-skel" style={{ height: 420 }} />;
  const stickVariants = d.variants.filter((v) => stickColor(v.skuCode));
  const formProblem = addressProblem(form);
  const canBuy = open && !!profile && !!variant && !variant.soldOut && !formProblem && !busy;
  const cur = step === "idle" ? 0 : step === "waiting" ? 1 : 2;

  return (
    <div className="fq-detail">
      <Link href="/drops" className="fq-back">
        ← 드롭 목록
      </Link>
      <div id="drop-buy" className="fq-detail-grid" style={{ scrollMarginTop: 16 }}>
        <div className="fq-detail-art">
          <Poster seed={d.dropId + 1} word={firstWord(d.title)} kicker={`DROP #${String(d.dropId).padStart(3, "0")}`} imageUrl={d.imageUrl} ratio="1 / 1" />
        </div>

        <div className="fq-detail-info">
          <DropStatusChip item={d} now={now} />
          <h2 className="fq-detail-title">{d.title}</h2>
          <p className="fq-desc">{d.description}</p>
          <p className="fq-price">
            <b className="fq-num">{won(Math.min(...d.variants.map((v) => v.priceAmount)))}</b>
            <span>부터</span>
          </p>
          <DropTimeline opensAt={d.opensAt} closesAt={d.closesAt} now={now} />

          <ol className="fq-steps" aria-label="구매 단계">
            {STEPS.map((s, i) => (
              <li key={s} className={i < cur ? "is-done" : i === cur ? "is-now" : ""}>
                <i>{i < cur ? "✓" : i + 1}</i>
                {s}
              </li>
            ))}
          </ol>

          {step === "idle" ? (
            <div className="fq-cta-box">
              <div className="fq-variants" role="radiogroup" aria-label="옵션">
                {d.variants.map((v) => (
                  <button
                    key={v.variantId}
                    type="button"
                    role="radio"
                    aria-checked={variantId === v.variantId}
                    disabled={busy || v.soldOut}
                    className={`fq-variant${variantId === v.variantId ? " is-on" : ""}`}
                    onClick={() => setVariantId(v.variantId)}
                  >
                    <b>{v.optionName}</b>
                    <span className="fq-num">{v.soldOut ? "현재 구매 불가" : won(v.priceAmount)}</span>
                  </button>
                ))}
              </div>

              {!profile ? (
                <Link href={`/login?next=${encodeURIComponent(`/drops/detail?id=${id}`)}`} className="fq-btn is-block">
                  로그인하고 결제하기
                </Link>
              ) : (
                <>
                  <AddressFields value={form} onChange={editForm} onLoadDefault={loadDefault} hasDefault={hasDefault} disabled={busy} />
                  {formProblem && (form.recipient || form.phone || form.address) ? <p className="fq-muted fq-note">{formProblem}</p> : null}
                  <button className="fq-btn is-hot is-block fq-big" disabled={!canBuy} onClick={enter}>
                    {open
                      ? busy ? (notice ? "다시 시도하는 중…" : "대기열 입장 중…") : !variant ? "옵션을 골라 주세요" : formProblem ? "배송지를 입력해 주세요" : "결제하기"
                      : msLeft(d.opensAt, now) > 0 ? `오픈까지 ${countdown(d.opensAt, now)}` : "판매 종료"}
                  </button>
                  {notice ? <p className="fq-muted fq-note" role="status">{notice}</p> : null}
                </>
              )}
              <p className="fq-muted fq-note">
                결제하기를 누르면 선택한 옵션의 대기열에 들어가요. 차례가 오면 재고를 확보하고 토스 결제창을 열어요.
              </p>
            </div>
          ) : null}

          {step === "waiting" ? (
            <>
              <div className="fq-queue" aria-live="polite">
                <QueueGauge pos={pos} pct={pct} />
                <p className="fq-muted">차례를 기다리는 중이에요. 차례가 오면 자동으로 결제를 준비해요.</p>
              </div>
              <div className="fq-card is-pad fq-order" style={{ marginTop: 12 }}>
                <div className="fq-eyebrow">선택한 옵션</div>
                <b>{variant?.optionName ?? "-"}</b>
                {confirmed && <AddressSummary value={confirmed} />}
              </div>
            </>
          ) : null}

          {step === "admitted" ? (
            <div className="fq-card is-pad fq-order">
              <p role="status">{retryReserve ? "재고 확보 결과를 다시 확인해 주세요." : notice ?? "차례가 왔어요. 재고를 확보하고 주문을 만들고 있어요…"}</p>
              <div className="fq-eyebrow">선택한 옵션</div>
              <b>{variant?.optionName ?? "-"}</b>
              {confirmed && <AddressSummary value={confirmed} />}
              {retryReserve && <button className="fq-btn is-hot is-block" disabled={busy} onClick={reserve}>결과 다시 확인</button>}
            </div>
          ) : null}

          {step === "consumed" ? (
            <div className="fq-card is-pad fq-done">
              <div className="fq-done-ico">Q</div>
              {done && doneStatus === "RESERVED" ? (
                <>
                  <h3>결제 대기 중인 주문이 있어요</h3>
                  <p className="fq-muted">주문번호 #{done.orderId}</p>
                  <dl className="fq-kv">
                    <dt>결제 금액</dt>
                    <dd className="fq-num">{won(done.amount)}</dd>
                    <dt>결제 기한</dt>
                    <dd className="fq-num">{countdown(done.expiresAt, now)}</dd>
                  </dl>
                  {busy ? <p role="status">토스 결제창을 준비하고 있어요…</p> : (
                    <button className="fq-btn is-grad is-block" disabled={msLeft(done.expiresAt, now) <= 0} onClick={() => void startPayment(done)}>
                      {msLeft(done.expiresAt, now) > 0 ? "결제창 다시 열기" : "결제 기한이 지났어요"}
                    </button>
                  )}
                  <Link
                    className="fq-link"
                    href={checkoutHref({ orderId: done.orderId, kind: "DROP", title: d.title, amount: done.amount, paymentExpiresAt: done.expiresAt, back: `/drops/detail?id=${id}` })}
                  >
                    주문 결제 화면에서 이어가기
                  </Link>
                  <button className="fq-btn is-ghost is-block" disabled={busy} onClick={cancelReservation}>구매 취소</button>
                </>
              ) : done ? (
                <>
                  <h3>{["EXPIRED", "RELEASED"].includes(doneStatus) ? "지난 주문이 끝났어요" : doneStatus === "DEADLINE" ? "결제 기한이 지났어요" : doneStatus === "CONFIRMED" ? "결제가 끝난 주문이 있어요" : "결제 확인 중이에요"}</h3>
                  <p className="fq-muted">
                    {["RESERVED", "EXPIRED", "RELEASED", "DEADLINE", "CONFIRMED"].includes(doneStatus)
                      ? `주문번호 #${done.orderId}의 상태는 주문 결제 화면에서 볼 수 있어요.`
                      : `주문번호 #${done.orderId}의 결제 결과를 확인하고 있어요. 확인이 끝나기 전에는 이 드롭을 새로 구매할 수 없어요.`}
                  </p>
                  <Link
                    className="fq-btn is-grad is-block"
                    href={checkoutHref({ orderId: done.orderId, kind: "DROP", title: d.title, amount: done.amount, paymentExpiresAt: done.expiresAt, back: `/drops/detail?id=${id}`, expired: doneStatus === "DEADLINE" })}
                  >
                    주문 상태 보기
                  </Link>
                  {["EXPIRED", "RELEASED"].includes(doneStatus) ? <button className="fq-btn is-ghost is-block" onClick={startOver}>옵션 다시 고르기</button> : null}
                </>
              ) : (
                <>
                  <h3>진행 중인 주문이 있어요</h3>
                  <p className="fq-muted">이 드롭에서 이미 재고를 확보한 주문이 있어요. 내 주문에서 이어서 결제해 주세요.</p>
                  <Link className="fq-btn is-grad is-block" href="/me/orders">내 주문 보기</Link>
                </>
              )}
            </div>
          ) : null}
        </div>
      </div>

      {stickVariants.length ? <SpecSheet /> : null}
      {stickVariants.length ? (
        <IntroGate><Lightstick
          buyHref="#drop-buy"
          colors={stickVariants.map((v) => stickColor(v.skuCode)!)}
          color={stickColor(variant?.skuCode) ?? stickColor(stickVariants[0].skuCode)!}
          onColor={(c) => {
            const v = stickVariants.find((x) => stickColor(x.skuCode) === c);
            if (v && !v.soldOut && !busy && step === "idle") setVariantId(v.variantId);
          }}
        /></IntroGate>
      ) : null}
    </div>
  );
}
