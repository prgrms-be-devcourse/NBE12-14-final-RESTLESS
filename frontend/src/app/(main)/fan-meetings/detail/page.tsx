"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import FanMeetingView from "./view";

// 정적 내보내기용 주소: ?id=
export default function Page() {
  return <QueryPage render={(q) => <FanMeetingView key={q.get("id")} id={numberParam(q, "id")} />} />;
}
