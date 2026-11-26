"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { checkoutHref } from "@/lib/orders";

export default function FailView({ orderId, code, message }: { orderId: number; code?: string; message?: string }) {
  // sessionStorage는 브라우저에만 있다. 렌더 중에 읽으면 서버 HTML(힌트 없음)이 hydration 뒤에도 남으므로 마운트 뒤에 읽는다
  const [hint, setHint] = useState<Record<string, unknown>>({});
  useEffect(() => {
    try {
      setHint(JSON.parse(sessionStorage.getItem(`checkout-hint-${orderId}`) ?? "{}"));
    } catch { /* 힌트 없이 돌아간다 */ }
  }, [orderId]);
  const cancelled = code === "PAY_PROCESS_CANCELED" || code === "USER_CANCEL";
  const href = checkoutHref({ orderId, kind: "DROP", title: "", amount: 0, ...hint } as Parameters<typeof checkoutHref>[0]);
  return (
    <div className="fq-pay">
      <div className="fq-card is-pad fq-done is-bad">
        <div className="fq-done-ico">!</div>
        <h3>{cancelled ? "결제를 취소했어요" : "결제하지 못했어요"}</h3>
        <p className="fq-muted">{cancelled ? "결제 기한 안에서 다시 시도할 수 있어요." : message ?? "알 수 없는 오류예요."}{code && !cancelled ? ` (${code})` : ""}</p>
        <Link href={href} className="fq-btn is-hot is-block fq-big">결제 화면으로 돌아가기</Link>
      </div>
    </div>
  );
}
