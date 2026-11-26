"use client";

import Link from "next/link";
import { useState } from "react";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { won } from "@/lib/format";
import { openTossPayment } from "@/lib/toss";
import type { CreditCharge, PreparedPayment, WalletView } from "@/lib/types";
import { useLoad } from "@/lib/hooks";

const shortcuts = [10_000, 50_000, 100_000];
const newKey = () => crypto.randomUUID();

export default function CreditsView() {
  const toast = useToast();
  const wallet = useLoad(() => api<WalletView>("/api/me/wallet"));
  const [rawAmount, setRawAmount] = useState("50000");
  const [busy, setBusy] = useState(false);
  const [completeAmount, setCompleteAmount] = useState<number | null>(null);
  const [review, setReview] = useState(false);
  const [chargeKey, setChargeKey] = useState(() => newKey());
  const [keyAmount, setKeyAmount] = useState<number | null>(50_000);

  const amount = /^\d+$/.test(rawAmount) ? Number(rawAmount) : NaN;
  const amountError = !rawAmount.trim()
    ? "충전 금액을 입력해 주세요."
    : !Number.isSafeInteger(amount)
      ? "금액은 정수로 입력해 주세요."
      : amount < 1_000 || amount > 1_000_000
        ? "1,000원 이상 1,000,000원 이하로 입력해 주세요."
        : amount % 1_000 !== 0
          ? "1,000원 단위로 입력해 주세요."
          : "";

  const updateAmount = (value: string) => {
    setRawAmount(value);
    const parsed = /^\d+$/.test(value) ? Number(value) : NaN;
    if (parsed !== keyAmount) {
      setChargeKey(newKey());
      setKeyAmount(Number.isSafeInteger(parsed) ? parsed : null);
    }
  };

  const pay = async () => {
    if (amountError || busy) return;
    setBusy(true);
    setCompleteAmount(null);
    setReview(false);
    try {
      let charge: CreditCharge;
      try {
        charge = await api<CreditCharge>("/api/me/credit-charges", {
          method: "POST", idempotent: chargeKey, body: { amount },
        });
      } catch (error) {
        const apiError = error as ApiError;
        if (apiError.code !== "PAYMENT_WINDOW_EXPIRED") throw error;
        const retryKey = newKey();
        setChargeKey(retryKey);
        setKeyAmount(amount);
        charge = await api<CreditCharge>("/api/me/credit-charges", {
          method: "POST", idempotent: retryKey, body: { amount },
        });
      }
      if (charge.status === "PAID") {
        setCompleteAmount(charge.amount);
        setChargeKey(newKey());
        setKeyAmount(null);
        void wallet.reload();
        return;
      }
      let prepared: PreparedPayment;
      try {
        prepared = await api<PreparedPayment>("/api/payments", {
          method: "POST", idempotent: newKey(),
          body: { payableType: charge.payable.payableType, payableId: charge.payable.payableId },
        });
      } catch (error) {
        if ((error as ApiError).code !== "PAYMENT_WINDOW_EXPIRED") throw error;
        const retryKey = newKey();
        setChargeKey(retryKey);
        setKeyAmount(amount);
        charge = await api<CreditCharge>("/api/me/credit-charges", {
          method: "POST", idempotent: retryKey, body: { amount },
        });
        if (charge.status === "PAID") {
          setCompleteAmount(charge.amount);
          setChargeKey(newKey()); setKeyAmount(null);
          void wallet.reload();
          return;
        }
        prepared = await api<PreparedPayment>("/api/payments", {
          method: "POST", idempotent: newKey(),
          body: { payableType: charge.payable.payableType, payableId: charge.payable.payableId },
        });
      }
      if (prepared.status === "APPROVED") {
        setCompleteAmount(prepared.amount);
        setChargeKey(newKey());
        setKeyAmount(null);
        void wallet.reload();
        return;
      }
      if (["PROCESSING", "UNKNOWN", "CANCEL_PENDING", "CANCEL_UNKNOWN"].includes(prepared.status)) {
        sessionStorage.setItem(`credit-charge-${charge.chargeId}`, JSON.stringify({
          paymentId: prepared.paymentId, chargeId: charge.chargeId, amount: charge.amount,
        }));
        setReview(true);
        return;
      }
      sessionStorage.setItem(`credit-charge-${charge.chargeId}`, JSON.stringify({
        paymentId: prepared.paymentId, chargeId: charge.chargeId, amount: charge.amount,
      }));
      await openTossPayment({
        pgOrderId: prepared.pgOrderId, amount: prepared.amount, orderName: prepared.orderName,
        orderId: charge.chargeId,
        successPath: `/me/credits/${charge.chargeId}/success`,
        failPath: `/me/credits/${charge.chargeId}/fail`,
      }, {
        onCancel: () => toast("결제를 취소했어요. 다시 시도할 수 있어요.", "info"),
        onError: (error) => toast((error as Error).message || "결제창을 열지 못했어요.", "err"),
      });
    } catch (error) {
      const apiError = error as ApiError;
      if (apiError.code === "INVALID_REQUEST") {
        toast("1,000원 이상 1,000,000원 이하의 1,000원 단위 금액을 입력해 주세요.", "err");
      } else if (apiError.code === "PAYMENT_WINDOW_EXPIRED") {
        toast("충전 주문 기한이 지나 새 주문을 만들지 못했어요. 다시 눌러 주세요.", "err");
        setChargeKey(newKey()); setKeyAmount(amount);
      } else if (apiError.code === "PAYMENT_UNDER_REVIEW") {
        setReview(true);
      } else if (apiError.code === "PAYMENT_NOT_PAYABLE") {
        toast("이미 충전됐거나 결제할 수 없는 주문이에요.", "err");
        setChargeKey(newKey()); setKeyAmount(null);
      } else if (apiError.isUnavailable) {
        toast("서버 응답을 확인할 수 없어요. 결제 상태를 확인해 주세요.", "err");
      } else toast(apiError.message, "err");
    } finally {
      setBusy(false);
    }
  };

  const w = wallet.data;
  return (
    <div className="fq-pay">
      <Link href="/me" className="fq-back">← 내 정보</Link>
      {completeAmount !== null ? (
        <section className="fq-card is-pad fq-done is-ok">
          <div className="fq-done-ico">✅</div><h2>충전 완료</h2>
          <p className="fq-muted">{won(completeAmount)} 크레딧이 지갑에 추가됐어요.</p>
          <div className="fq-kv-box fq-between"><span>사용 가능 크레딧</span><strong className="fq-num">{won(w?.availableAmount ?? 0)}</strong></div>
          <Link href="/auctions" className="fq-btn is-grad is-block">경매 보러 가기</Link>
          <Link href="/me" className="fq-btn is-ghost is-block">내 정보</Link>
        </section>
      ) : review ? (
        <section className="fq-card is-pad fq-done is-wait"><div className="fq-done-ico">⏳</div><h2>결제 확인 중</h2>
          <p className="fq-muted">결과가 확인될 때까지 새 결제를 보내지 않습니다.</p>
          <Link href="/me" className="fq-btn is-ghost is-block">내 정보</Link>
        </section>
      ) : (
        <section className="fq-card is-pad" style={{ maxWidth: 620, margin: "0 auto" }}>
          <span className="fq-eyebrow">경매 크레딧</span><h2>크레딧 충전</h2>
          <p className="fq-muted">1원당 1크레딧이 충전돼요.</p>
          <div className="fq-kv-box" style={{ margin: "18px 0" }}>
            <strong>사용 가능 크레딧　<span className="fq-num">{w ? won(w.availableAmount) : "불러오는 중…"}</span></strong>
            <p className="fq-muted">전체 잔액 {w ? won(w.balanceAmount) : "…"} · 입찰에 묶인 금액 {w ? won(w.reservedAmount) : "…"}</p>
          </div>
          <div className="fq-row" style={{ marginBottom: 16 }}>
            {shortcuts.map((value) => <button key={value} type="button" aria-pressed={amount === value}
              className={`fq-btn is-sm ${amount === value ? "is-grad" : "is-ghost"}`} onClick={() => updateAmount(String(value))}>{won(value)}</button>)}
          </div>
          <div className="fq-field">
            <label htmlFor="credit-amount">충전 금액</label>
            <input id="credit-amount" className="fq-input" inputMode="numeric" value={rawAmount}
              aria-invalid={!!amountError} aria-describedby="credit-amount-error" onChange={(event) => updateAmount(event.target.value)} />
            {amountError ? <p id="credit-amount-error" className="fq-err" role="alert">{amountError}</p> : null}
          </div>
          <div className="fq-kv-box fq-between" style={{ margin: "16px 0" }}>
            <span>충전 후 사용 가능 크레딧</span>
            <strong className="fq-num">{w && !amountError ? won(w.availableAmount + amount) : "—"}</strong>
          </div>
          <p className="fq-note fq-muted">충전한 크레딧은 환불되지 않아요.</p>
          <button className="fq-btn is-hot is-block fq-big" disabled={busy || !!amountError} onClick={pay}>
            {busy ? "결제 준비 중…" : `${amountError ? "금액을 확인해 주세요" : won(amount)} 결제하기`}
          </button>
        </section>
      )}
    </div>
  );
}
