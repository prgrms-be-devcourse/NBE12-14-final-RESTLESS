"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import SuccessView from "./view";

// 토스 successUrl 복귀: ?order=(우리 주문 id)&paymentType=&orderId=(pgOrderId)&paymentKey=&amount=
// 토스가 orderId를 붙이므로 우리 주문 id는 order로 넘긴다.
export default function Page() {
  return (
    <QueryPage
      render={(q) => (
        <SuccessView
          orderId={numberParam(q, "order")}
          paymentKey={q.get("paymentKey") ?? undefined}
          pgOrderId={q.get("orderId") ?? undefined}
          amount={q.get("amount") ? Number(q.get("amount")) : undefined}
        />
      )}
    />
  );
}
