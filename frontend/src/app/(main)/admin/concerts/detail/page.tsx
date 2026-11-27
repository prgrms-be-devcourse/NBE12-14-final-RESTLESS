"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import ConcertManageView from "./view";

// 정적 내보내기용 주소: ?id=
export default function Page() {
  return <QueryPage render={(q) => <ConcertManageView key={q.get("id")} id={numberParam(q, "id")} />} />;
}
