"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import LoadError from "@/components/LoadError";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { burstFrom } from "@/lib/confetti";
import { countdown, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import { openTossPayment } from "@/lib/toss";
import { ORDER_KIND, ORDER_STATUS, isPaymentDeadlineError, orderTitle } from "@/lib/orders";
import type { MyFanMeetingOrder, OrderDetail, PaymentDetail, PreparedPayment } from "@/lib/types";

type Phase = "FORM" | "PAID" | "FAILED" | "PAYMENT_REVIEW" | "REFUND_REVIEW" | "REFUNDED";
type Hint = { kind?: string; title?: string; amount?: number; expires?: string; back?: string; eventId?: number; expired?: boolean };
const newKey = () => crypto.randomUUID();

export default function CheckoutView({ orderId, hint }: { orderId: number; hint: Hint }) {
  const now = useNow();
  const toast = useToast();
  const { data: order, error, loading, reload } = useLoad<OrderDetail>(
    async () => {
      if (hint.kind === "FAN_MEETING" && hint.eventId) {
        const fmOrder = await api<MyFanMeetingOrder>(`/api/fan-meetings/${hint.eventId}/orders/me`);
        if (fmOrder.orderId !== orderId) throw new ApiError(404, { code: "ORDER_NOT_FOUND", message: "주문을 찾을 수 없습니다." });
        return {
          orderId: fmOrder.orderId,
          orderType: "TICKET" as const,
          status: fmOrder.status as OrderDetail["status"],
          totalAmount: fmOrder.amount,
          createdAt: fmOrder.issuedAt,
          lines: [{ itemType: "FAN_MEETING", itemName: hint.title ?? `팬미팅 #${hint.eventId}`, optionName: "", unitPrice: fmOrder.amount, quantity: 1 }],
          shipping: null,
          payable: { payableType: "FAN_MEETING", payableId: fmOrder.orderId },
          payableExpiresAt: fmOrder.paymentDeadline,
          payment: null,
        };
      }
      return api<OrderDetail>(`/api/orders/${orderId}`);
    },
    [orderId],
  );
  const [phase, setPhase] = useState<Phase>("FORM");
  const [busy, setBusy] = useState(false);
  const [paymentId, setPaymentId] = useState<number | null>(null);
  const paymentIdRef = useRef<number | null>(null);
  const [paymentStatus, setPaymentStatus] = useState<string | null>(null);
  const [refundStatus, setRefundStatus] = useState<string | null>(null);
  const [refundFailed, setRefundFailed] = useState(false);
  // 서버가 결제 기한 경과로 거절했다. 브라우저 시계가 늦어도 기한이 지난 것으로 보여 준다
  const [serverExpired, setServerExpired] = useState(!!hint.expired);
  const prepareKey = useRef(newKey());
  const refundKey = useRef<string | null>(null);
  const ctaRef = useRef<HTMLButtonElement>(null);
  const backHref = hint.back ?? "/me/orders";

  useEffect(() => {
    if (!order?.payment) return;
    paymentIdRef.current = order.payment.paymentId;
    setPaymentId(order.payment.paymentId);
    if (order.status === "REFUND_PENDING" || ["CANCEL_PENDING", "CANCEL_UNKNOWN"].includes(order.payment.status)) {
      setRefundStatus(order.payment.status === "CANCEL_PENDING" ? "REQUESTED" : "UNKNOWN"); setPhase("REFUND_REVIEW");
    } else if (order.status === "REFUND_REVIEW") {
      setRefundFailed(true); setPhase("PAID");
    } else if (["PROCESSING", "UNKNOWN"].includes(order.payment.status)) {
      setPhase("PAYMENT_REVIEW"); setPaymentStatus(order.payment.status);
    } else if (order.payment.status === "APPROVED") setPhase("PAID");
    else if (order.payment.status === "CANCELLED") setPhase("REFUNDED");
    else if (order.payment.status === "FAILED") setPhase("FAILED");
  }, [order?.payment?.paymentId, order?.payment?.status, order?.status]);

  useEffect(() => {
    if (phase !== "PAYMENT_REVIEW" || paymentId === null) return;
    let stopped = false;
    const poll = async () => {
      while (!stopped) {
        try {
          const payment = await api<PaymentDetail>(`/api/payments/${paymentId}`);
          if (stopped) return;
          setPaymentStatus(payment.status);
          if (payment.status === "APPROVED") { setPhase("PAID"); void reload(); return; }
          if (payment.status === "FAILED") { setPhase("FAILED"); void reload(); return; }
          if (payment.status === "CANCELLED") { setPhase("REFUNDED"); void reload(); return; }
        } catch { /* 다음 주기에 상태를 다시 확인 */ }
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    };
    void poll();
    return () => { stopped = true; };
  }, [phase, paymentId, reload]);

  useEffect(() => {
    if (phase !== "REFUND_REVIEW" || paymentId === null) return;
    let stopped = false;
    const poll = async () => {
      while (!stopped) {
        try {
          const payment = await api<PaymentDetail>(`/api/payments/${paymentId}`);
          if (stopped) return;
          // 응답을 받지 못한 새 요청에서는 직전 실패 이력이 최신 요청인지 단정할 수 없다.
          const status = payment.refund?.status === "FAILED" && refundKey.current && refundStatus === "UNKNOWN"
            ? "UNKNOWN" : payment.refund?.status ?? "UNKNOWN";
          setRefundStatus(status);
          if (status === "COMPLETED") { setPhase("REFUNDED"); void reload(); return; }
          if (status === "FAILED") { refundKey.current = null; setRefundFailed(true); setPhase("PAID"); void reload(); return; }
        } catch { /* 다음 주기에 다시 조회 */ }
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    };
    void poll();
    return () => { stopped = true; };
  }, [phase, paymentId, refundStatus, reload]);

  const pay = async () => {
    if (!order) return;
    if (!order.payable) { toast("이 주문은 지금 결제할 수 없어요.", "err"); return; }
    setBusy(true);
    try {
      const payable = order.payable!;
      const prepared = await api<PreparedPayment>("/api/payments", {
        method: "POST",
        idempotent: prepareKey.current,
        body: { payableType: payable.payableType, payableId: payable.payableId },
      });
      setPaymentId(prepared.paymentId);
      paymentIdRef.current = prepared.paymentId;
      if (prepared.status === "APPROVED") { setPhase("PAID"); void reload(); return; }
      if (["PROCESSING", "UNKNOWN", "CANCEL_PENDING", "CANCEL_UNKNOWN"].includes(prepared.status)) {
        setPaymentStatus(prepared.status); setPhase("PAYMENT_REVIEW"); return;
      }
      if (prepared.status === "FAILED") { setPhase("FAILED"); return; }
      // READY: 토스 결제창을 연다. 결제하기를 누르면 successUrl 화면이 서버에 승인을 요청한다.
      sessionStorage.setItem(`checkout-hint-${orderId}`, JSON.stringify(hint));
      await openTossPayment(
        { pgOrderId: prepared.pgOrderId, amount: prepared.amount, orderName: prepared.orderName, orderId },
        {
          onCancel: () => toast("결제를 취소했어요. 다시 시도할 수 있어요.", "info"),
          onError: (err) => toast((err as Error).message || "결제창을 열지 못했어요.", "err"),
        },
      );
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "PAYMENT_FAILED") {
        setPhase("FAILED");
        prepareKey.current = newKey();
      } else if (err.code === "PAYMENT_REJECTED") {
        const refund = err.details?.refund;
        setPhase(refund === "COMPLETED" ? "REFUNDED" : "PAYMENT_REVIEW");
        void reload();
        toast(refund === "COMPLETED" ? "주문을 처리할 수 없어 결제를 취소했어요. 결제 금액은 환불돼요." : "주문 결과와 환불 여부를 확인하고 있어요.", "info");
      } else if (isPaymentDeadlineError(err)) {
        setServerExpired(true);
        void reload().catch(() => undefined);
        toast("결제 기한이 지났어요.", "err");
      } else if (err.isUnavailable && paymentIdRef.current !== null) {
        setPhase("PAYMENT_REVIEW");
        toast("결제 결과를 확인하고 있어요.", "info");
      } else toast(err.message, "err");
    } finally { setBusy(false); }
  };

  const retry = () => {
    prepareKey.current = newKey();
    setPhase("FORM");
    toast("결제를 다시 진행해 주세요.");
  };

  const refund = async () => {
    if (paymentId === null || busy) return;
    if (phase === "PAID" && !window.confirm("예약 또는 주문 전체를 취소하고 전액 환불을 요청할까요?")) return;
    setBusy(true);
    try {
      refundKey.current ??= newKey();
      const result = await api<{ refundId: number; status: string }>(`/api/payments/${paymentId}/refunds`, {
        method: "POST", idempotent: refundKey.current, body: { reason: "사용자 환불 요청" },
      });
      if (result.status === "COMPLETED") { setPhase("REFUNDED"); void reload(); }
      else if (result.status === "FAILED") { refundKey.current = null; setRefundFailed(true); setPhase("PAID"); }
      else { setRefundStatus(result.status); setPhase("REFUND_REVIEW"); }
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "PAYMENT_REFUND_REJECTED" || err.code === "PAYMENT_NOT_REFUNDABLE") { refundKey.current = null; if (err.code === "PAYMENT_REFUND_REJECTED") setRefundFailed(true); toast(err.message, "err"); void reload().catch(() => undefined); }
      else if (err.code === "PAYMENT_REFUND_IN_PROGRESS" || err.code === "PAYMENT_ALREADY_REFUNDED") { setRefundStatus("UNKNOWN"); setPhase("REFUND_REVIEW"); }
      else if (err.isUnavailable) { setPhase("REFUND_REVIEW"); setRefundStatus("UNKNOWN"); }
      else toast(err.message, "err");
    } finally { setBusy(false); }
  };

  if (error) return <LoadError error={error} backHref={backHref} />;
  if (loading || !order) return <div className="fq-skel" style={{ height: 420, maxWidth: 560, margin: "0 auto" }} />;
  const kind = hint.kind === "FAN_MEETING" ? ORDER_KIND.FAN_MEETING : ORDER_KIND[order.orderType];
  const title = orderTitle(order);
  const payableExpiresAt = order.payableExpiresAt;
  const left = serverExpired ? 0 : payableExpiresAt ? msLeft(payableExpiresAt, now) : Infinity;
  const closed = ["EXPIRED", "CANCELLED", "REFUNDED", "REFUND_PENDING", "REFUND_REVIEW"].includes(order.status);

  return (
    <div className="fq-pay">
      <Link href={backHref} className="fq-back">← 돌아가기</Link>
      

      {phase === "PAID" ? (
        <div className="fq-receipt-wrap">
          {/* 결제 완료: 종이 영수증 한 장. 값은 모두 서버가 준 주문 정보다 */}
          <article className="fq-receipt" aria-label="결제 영수증">
            <header>
              <span>FANQ RECEIPT</span>
              <i className="fq-receipt-stamp" aria-hidden="true">PAID</i>
            </header>
            <h3>결제가 완료됐어요</h3>
            <p className="fq-receipt-item">{title}</p>
            <dl>
              <div><dt>주문번호</dt><dd className="fq-num">#{order.orderId}</dd></div>
              <div><dt>구분</dt><dd>{kind.label}</dd></div>
              {order.shipping ? <div><dt>받는 분</dt><dd>{order.shipping.recipient}</dd></div> : null}
              <div className="is-total"><dt>결제 금액</dt><dd className="fq-num">{won(order.totalAmount)}</dd></div>
            </dl>
            <p className="fq-receipt-note">결제 내역은 주문과 참여 내역에서 다시 볼 수 있어요.</p>
          </article>
          {refundFailed ? <p className="fq-muted" role="status">환불이 거절됐어요. 환불 가능 조건을 확인한 뒤 다시 요청할 수 있어요.</p> : null}
          {(order.orderType === "DROP" || (order.orderType === "TICKET" && order.payable?.payableType === "TICKET")) && paymentId !== null ? <><p className="fq-note fq-muted">{order.orderType === "TICKET" ? "예약한 좌석 전체가 취소되며 전액 환불돼요. 공연 시작 1시간 전까지 요청할 수 있어요." : "주문 전체가 전액 환불돼요."}</p><button className="fq-btn is-ghost is-block" disabled={busy} onClick={refund}>{busy ? "환불 요청 중…" : refundFailed ? "전액 환불 다시 요청" : "전액 환불 요청"}</button></> : null}
          <Link href="/me/orders" className="fq-btn is-grad is-block">주문과 참여 내역 보기</Link>
        </div>
      ) : phase === "FAILED" ? (
        <div className="fq-card is-pad fq-done is-bad"><div className="fq-done-ico">!</div><span className="fq-chip is-live" style={{ alignSelf: "center" }}>결제 실패</span><h3>{left > 0 ? "결제가 거절됐어요" : "결제 기한이 지났어요"}</h3><p className="fq-muted">{left > 0 ? `${payableExpiresAt ? `결제 기한이 ${countdown(payableExpiresAt, now)} 남았어요` : "결제 기한이 남아 있어요"}. 다시 결제할 수 있어요.` : "결제 기한이 지났어요."}</p><button className="fq-btn is-hot is-block fq-big" disabled={left <= 0} onClick={retry}>다시 시도</button></div>
      ) : phase === "PAYMENT_REVIEW" || phase === "REFUND_REVIEW" ? (
        <div className="fq-card is-pad fq-done is-wait"><div className="fq-done-ico">…</div><span className="fq-chip is-warn" style={{ alignSelf: "center" }}>{phase === "PAYMENT_REVIEW" ? "결제 확인 중" : "환불 확인 중"}</span><h3>{phase === "PAYMENT_REVIEW" ? "결과를 확인하고 있어요" : "환불 결과를 확인하고 있어요"}</h3><p className="fq-muted">{phase === "PAYMENT_REVIEW" ? `결제 상태를 확인하고 있어요 (${paymentStatus ?? "확인 중"}).` : refundStatus === "REQUESTED" ? "환불 요청을 처리하고 있어요. 결과가 확인되면 상태가 바뀝니다." : "환불 결과가 아직 확정되지 않았어요. 자동으로 다시 확인하고 있어요."}</p>{phase === "REFUND_REVIEW" && refundKey.current && refundStatus === "UNKNOWN" ? <button className="fq-btn is-ghost is-block" disabled={busy} onClick={refund}>{busy ? "확인 중…" : "같은 환불 요청 확인"}</button> : null}<Link href="/me/orders" className="fq-btn is-ghost is-block">주문과 참여 내역으로</Link></div>
      ) : phase === "REFUNDED" ? (
        <div className="fq-card is-pad fq-done is-ok"><div className="fq-done-ico">↩</div><span className="fq-chip is-mint">환불 완료</span><h3>전액 환불이 확인됐어요</h3><Link href="/me/orders" className="fq-btn is-grad is-block">주문과 참여 내역 보기</Link></div>
      ) : (
        <div className="fq-card is-pad fq-done" style={{ textAlign: "left" }}><div className="fq-between"><div><span className="fq-eyebrow">ORDER #{order.orderId}, {kind.icon} {kind.label}</span><h3 style={{ marginTop: 4 }}>공통 결제</h3></div><span className={`fq-chip ${ORDER_STATUS[order.status]?.tone ?? ""}`}>{ORDER_STATUS[order.status]?.label ?? order.status}</span></div>
          <dl className="fq-kv"><dt>상품</dt><dd>{title}</dd>{payableExpiresAt && !closed ? <><dt>결제 기한까지</dt><dd className="fq-num">{left > 0 ? countdown(payableExpiresAt, now) : "기한 지남"}</dd></> : null}</dl>
          <div className="fq-amount-lock"><div><small>결제 금액</small><b className="fq-num">{won(order.totalAmount)}</b></div><span className="fq-chip">최종 금액</span></div>
          {closed ? <button className="fq-btn is-ghost is-block fq-big" disabled>{ORDER_STATUS[order.status].label}된 주문이에요</button> : <button ref={ctaRef} className="fq-btn is-hot is-block fq-big" disabled={busy || left <= 0} onClick={pay}>{left <= 0 ? "결제 기한이 지났어요" : busy ? "결제 요청 중…" : `${won(order.totalAmount)} 결제하기`}</button>}
          <p className="fq-note fq-muted">결제하기를 누르면 토스페이먼츠 결제창이 열려요.</p>
        </div>
      )}
    </div>
  );
}
