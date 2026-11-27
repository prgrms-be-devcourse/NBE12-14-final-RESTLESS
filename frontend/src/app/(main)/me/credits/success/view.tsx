"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { ConfirmedPayment, PaymentDetail, WalletView } from "@/lib/types";
import { won } from "@/lib/format";

type Phase = "CONFIRMING" | "REVIEW" | "PAID" | "FAILED" | "ERROR";

export default function SuccessView({ chargeId, paymentKey, pgOrderId, amount }: {
  chargeId: number; paymentKey?: string; pgOrderId?: string; amount?: number;
}) {
  const started = useRef(false);
  const paymentIdRef = useRef<number | null>(null);
  const [phase, setPhase] = useState<Phase>("CONFIRMING");
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [storedAmount, setStoredAmount] = useState<number | null>(null);
  const [errorText, setErrorText] = useState("");

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (!Number.isSafeInteger(chargeId) || chargeId <= 0 || !paymentKey || !pgOrderId ||
        amount === undefined || !Number.isSafeInteger(amount) || amount <= 0) {
      setPhase("ERROR"); setErrorText("결제 복귀 정보가 없거나 올바르지 않아요."); return;
    }
    // 승인 요청은 저장 정보와 상관없이 보낸다. 결제창에서 결제를 마쳤는데 승인을 안 보내면 충전이 끝나지 않는다.
    // 저장 정보(paymentId)는 승인 응답이 끊겼을 때 결제 상태를 조회하는 데만 쓴다.
    setStoredAmount(amount);
    void api<ConfirmedPayment>("/api/payments/confirm", {
      method: "POST", body: { paymentKey, orderId: pgOrderId, amount },
    }).then(async (result) => {
      paymentIdRef.current = result.paymentId;
      if (result.status === "APPROVED") {
        const freshWallet = await api<WalletView>("/api/me/wallet");
        setWallet(freshWallet); setPhase("PAID");
      } else setPhase("REVIEW");
    }).catch((reason: unknown) => {
      const apiError = reason as ApiError;
      if (apiError.code === "PAYMENT_FAILED") setPhase("FAILED");
      else if (apiError.isUnavailable || apiError.status === 202) setPhase("REVIEW");
      else { setPhase("ERROR"); setErrorText(apiError.message || "결제 결과를 확인하지 못했어요."); }
    });
  }, [chargeId, paymentKey, pgOrderId, amount]);

  useEffect(() => {
    if (phase !== "REVIEW") return;
    let stopped = false;
    const poll = async () => {
      let paymentId = paymentIdRef.current;
      if (paymentId === null) {
        try {
          const saved = JSON.parse(sessionStorage.getItem(`credit-charge-${chargeId}`) ?? "{}") as { paymentId?: number };
          if (Number.isSafeInteger(saved.paymentId)) paymentId = saved.paymentId!;
        } catch { /* 저장 정보 없음 */ }
      }
      if (paymentId === null) {
        setPhase("ERROR"); setErrorText("결제 결과를 확인하지 못했어요. 잠시 뒤 내 정보에서 크레딧을 확인해 주세요."); return;
      }
      while (!stopped) {
        try {
          const payment = await api<PaymentDetail>(`/api/payments/${paymentId}`);
          if (stopped) return;
          if (payment.status === "APPROVED") {
            const freshWallet = await api<WalletView>("/api/me/wallet");
            if (stopped) return;
            setWallet(freshWallet); setPhase("PAID"); return;
          }
          if (payment.status === "FAILED") { setPhase("FAILED"); return; }
        } catch { /* keep checking every three seconds */ }
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    };
    void poll();
    return () => { stopped = true; };
  }, [phase, chargeId]);

  return <div className="fq-pay"><section className={`fq-card is-pad fq-done ${phase === "PAID" ? "is-ok" : phase === "FAILED" || phase === "ERROR" ? "is-bad" : "is-wait"}`}>
    {phase === "PAID" ? <>
      <div className="fq-done-ico">✅</div><h2>충전 완료</h2>
      <p className="fq-muted">{won(storedAmount ?? amount ?? 0)} 크레딧이 지갑에 추가됐어요.</p>
      <div className="fq-kv-box fq-between"><span>사용 가능 크레딧</span><strong className="fq-num">{won(wallet?.availableAmount ?? 0)}</strong></div>
      <Link href="/auctions" className="fq-btn is-grad is-block">경매 보러 가기</Link>
      <Link href="/me" className="fq-btn is-ghost is-block">내 정보</Link>
    </> : phase === "FAILED" ? <>
      <div className="fq-done-ico">⚠️</div><h2>결제가 거절됐어요</h2>
      <Link href="/me/credits" className="fq-btn is-hot is-block">다시 충전하기</Link>
    </> : phase === "ERROR" ? <>
      <div className="fq-done-ico">⚠️</div><h2>결제 정보를 확인할 수 없어요</h2>
      <p className="fq-muted">{errorText}</p><Link href="/me/credits" className="fq-btn is-ghost is-block">충전 화면으로</Link>
    </> : <>
      <div className="fq-done-ico">⏳</div><h2>{phase === "REVIEW" ? "결제 확인 중" : "결제를 확인하고 있어요"}</h2>
      <p className="fq-muted">확인이 끝날 때까지 새 결제 요청을 보내지 않습니다.</p>
    </>}
  </section></div>;
}
