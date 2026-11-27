"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import FailView from "./view";

// 토스 failUrl 복귀: ?charge=(충전 id)&code=&message=
export default function Page() {
  return (
    <QueryPage
      render={(q) => (
        <FailView chargeId={numberParam(q, "charge")} code={q.get("code") ?? undefined} message={q.get("message") ?? undefined} />
      )}
    />
  );
}
