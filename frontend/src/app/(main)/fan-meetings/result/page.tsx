"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import ResultView from "./view";

// 정적 내보내기용 주소: ?id=
export default function Page() {
  return <QueryPage render={(q) => <ResultView key={q.get("id")} id={numberParam(q, "id")} />} />;
}
