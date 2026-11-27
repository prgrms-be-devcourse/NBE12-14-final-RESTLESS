"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import SuccessView from "./view";

// 토스 successUrl 복귀: ?charge=(충전 id)&orderId=(pgOrderId)&paymentKey=&amount=
export default function Page() {
  return (
    <QueryPage
      render={(q) => (
        <SuccessView
          chargeId={numberParam(q, "charge")}
          paymentKey={q.get("paymentKey") ?? undefined}
          pgOrderId={q.get("orderId") ?? undefined}
          amount={q.get("amount") === null ? undefined : Number(q.get("amount"))}
        />
      )}
    />
  );
}
