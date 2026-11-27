"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import FailView from "./view";

// 토스 failUrl 복귀: ?order=(우리 주문 id)&code=&message=&orderId=(pgOrderId)
export default function Page() {
  return (
    <QueryPage
      render={(q) => (
        <FailView orderId={numberParam(q, "order")} code={q.get("code") ?? undefined} message={q.get("message") ?? undefined} />
      )}
    />
  );
}
