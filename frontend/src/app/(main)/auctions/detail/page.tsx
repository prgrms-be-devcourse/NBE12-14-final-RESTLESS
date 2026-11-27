"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import AuctionView from "./view";

// 정적 내보내기용 주소: ?id=
export default function Page() {
  return <QueryPage render={(q) => <AuctionView key={q.get("id")} id={numberParam(q, "id")} />} />;
}
