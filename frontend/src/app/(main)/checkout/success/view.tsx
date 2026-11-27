"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { checkoutHref, isPaymentDeadlineError } from "@/lib/orders";

type Props = { orderId: number; paymentKey?: string; pgOrderId?: string; amount?: number };

/** 토스 결제 성공 복귀 화면. 서버에 승인을 요청하고, 결과는 결제 화면이 서버 상태로 보여 준다. */
export default function SuccessView({ orderId, paymentKey, pgOrderId, amount }: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (!paymentKey || !pgOrderId || amount === undefined || Number.isNaN(amount)) {
      setError("결제 결과 정보가 올바르지 않아요.");
      return;
    }
    let hint: Record<string, unknown> = {};
    try {
      hint = JSON.parse(sessionStorage.getItem(`checkout-hint-${orderId}`) ?? "{}");
    } catch { /* 힌트가 없어도 서버 주문 조회로 화면을 그린다 */ }
    let expired = false;
    const back = () => router.replace(checkoutHref({ orderId, kind: "DROP", title: "", amount, ...hint, expired } as Parameters<typeof checkoutHref>[0]));
    api("/api/payments/confirm", { method: "POST", body: { paymentKey, orderId: pgOrderId, amount } })
      .catch((e) => {
        // 거절(409), 처리 중, 응답 끊김 모두 서버 결제 상태가 기준이다. 결제 화면이 그 상태를 읽는다.
        if (!(e instanceof ApiError)) throw e;
        // 기한 뒤(PAYMENT_WINDOW_EXPIRED)와 드롭 기한과 같은 시각(DROP_NOT_PAYABLE)은 같은 기한 경과로 보여 준다
        expired = isPaymentDeadlineError(e);
      })
      .then(back, () => setError("결제 결과를 확인하지 못했어요. 주문 내역에서 상태를 확인해 주세요."));
  }, [orderId, paymentKey, pgOrderId, amount, router]);

  return (
    <div className="fq-pay">
      <div className="fq-card is-pad fq-done is-wait">
        <div className="fq-done-ico">…</div>
        <h3>{error ?? "결제를 확인하고 있어요"}</h3>
        <p className="fq-muted">{error ? "" : "화면을 닫지 말고 잠시만 기다려 주세요."}</p>
      </div>
    </div>
  );
}
