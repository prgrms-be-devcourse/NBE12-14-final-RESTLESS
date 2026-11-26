"use client";

import Link from "next/link";

export default function FailView({ chargeId, code, message }: { chargeId: number; code?: string; message?: string }) {
  const cancelled = code === "PAY_PROCESS_CANCELED" || code === "USER_CANCEL";
  return <div className="fq-pay"><section className="fq-card is-pad fq-done is-bad">
    <div className="fq-done-ico">⚠️</div>
    <h2>{cancelled ? "결제를 취소했어요" : "결제하지 못했어요"}</h2>
    <p className="fq-muted">{cancelled ? "충전 화면에서 다시 결제할 수 있어요." : `${message ?? "알 수 없는 오류예요."}${code ? ` (${code})` : ""}`}</p>
    <Link href="/me/credits" className="fq-btn is-hot is-block fq-big">충전 화면으로 돌아가기</Link>
    <span className="fq-note fq-muted">충전 주문 #{chargeId}</span>
  </section></div>;
}
