"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import SeatsView from "./view";

// 정적 내보내기용 주소: ?id=&max=&concert=
export default function Page() {
  return (
    <QueryPage
      render={(q) => (
        <SeatsView key={q.get("id")} id={numberParam(q, "id")} max={Number(q.get("max")) || 4} concertId={q.get("concert")} />
      )}
    />
  );
}
