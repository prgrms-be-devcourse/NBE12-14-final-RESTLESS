"use client";

import { QueryPage, numberParam } from "@/lib/query-page";
import CheckoutView from "./view";

// 정적 내보내기용 주소: ?order=&kind=&title=&amount=&expires=&back=&eventId=&expired=
export default function Page() {
  return (
    <QueryPage
      render={(q) => {
        const back = q.get("back");
        const eventId = q.get("eventId");
        return (
          <CheckoutView
            key={q.get("order")}
            orderId={numberParam(q, "order")}
            hint={{
              kind: q.get("kind") ?? undefined,
              title: q.get("title") ?? undefined,
              amount: q.get("amount") ? Number(q.get("amount")) : undefined,
              expires: q.get("expires") ?? undefined,
              back: back?.startsWith("/") ? back : undefined,
              eventId: eventId ? Number(eventId) : undefined,
              expired: q.get("expired") === "1",
            }}
          />
        );
      }}
    />
  );
}
