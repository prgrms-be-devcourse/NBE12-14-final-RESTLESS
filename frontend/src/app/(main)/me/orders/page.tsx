"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useState } from "react";
import Tabs from "@/components/Tabs";
import LoadError from "@/components/LoadError";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { dateTime, won } from "@/lib/format";
import { useLoad } from "@/lib/hooks";
import { ORDER_KIND, ORDER_STATUS, checkoutHref, orderTitle } from "@/lib/orders";
import type { OrderPage } from "@/lib/types";

type F = "ALL" | "ACTIVE" | "DONE" | "ENDED";
const GROUP: Record<Exclude<F, "ALL">, string[]> = {
  ACTIVE: ["PENDING_PAYMENT", "PAYMENT_CONFIRMING", "PAYMENT_REVIEW", "REFUND_PENDING", "REFUND_REVIEW"],
  DONE: ["PAID"],
  ENDED: ["CANCELLED", "REFUNDED"],
};

export default function OrdersPage() {
  const { profile, ready } = useAuth();
  if (!ready) return <div className="fq-skel" style={{ height: 300 }} />;
  if (!profile)
    return (
      <div className="fq-card is-pad fq-empty">
        <LineArt kind="box" />
        <h2>로그인하면 주문 내역을 볼 수 있어요</h2>
        <Link className="fq-btn is-grad" href="/login">
          로그인
        </Link>
      </div>
    );
  return <Orders />;
}

function Orders() {
  const [f, setF] = useState<F>("ALL");
  const { data, error } = useLoad(() => api<OrderPage>("/api/me/orders?size=100").then((page) => page.items));
  const all = data ?? [];
  const list = f === "ALL" ? all : all.filter((o) => GROUP[f].includes(o.status));
  const count = (k: Exclude<F, "ALL">) => all.filter((o) => GROUP[k].includes(o.status)).length;

  return (
    <>
      <Link href="/me" className="fq-back">
        ← 마이페이지
      </Link>
      <p className="fq-lead">팬미팅, 티켓, 드롭 주문을 한곳에서 확인해요.</p>
      <Tabs
        label="주문 상태 필터"
        value={f}
        onChange={setF}
        items={[
          { value: "ALL", label: "전체", count: all.length },
          { value: "ACTIVE", label: "진행 중", count: count("ACTIVE") },
          { value: "DONE", label: "확정", count: count("DONE") },
          { value: "ENDED", label: "종료", count: count("ENDED") },
        ]}
      />
      <div className="fq-section" style={{ marginTop: 22 }}>
        {error ? <LoadError error={error} /> : !data ? (
          <div className="fq-skel" style={{ height: 240 }} />
        ) : list.length === 0 ? (
          <p className="fq-muted">해당하는 주문이 없어요</p>
        ) : (
          <ul className="fq-orders">
            {list.map((o, i) => {
              const s = ORDER_STATUS[o.status];
              const k = ORDER_KIND[o.orderType];
              const pending = o.status === "PENDING_PAYMENT";
              return (
                <li key={o.orderId} className="fq-card fq-order" data-status={o.status} style={{ animationDelay: `${i * 60}ms` }}>
                  <div className="fq-order-b">
                    <div className="fq-row">
                      <span className={`fq-chip ${s.tone}`}>{s.label}</span>
                      {pending ? <span className="fq-muted" style={{ fontSize: 13 }}>주문 상세에서 결제 기한 확인</span> : null}
                    </div>
                    <h3>
                      {k.icon} {orderTitle(o)}
                    </h3>
                    <small>
                      #{o.orderId}, {k.label}, {dateTime(o.createdAt)}, {won(o.totalAmount)}
                    </small>
                  </div>
                  <div className="fq-order-r">
                    {o.status === "PENDING_PAYMENT" ? (
                      <Link className="fq-btn is-hot is-sm" href={checkoutHref({ orderId: o.orderId, kind: o.orderType, title: orderTitle(o), amount: o.totalAmount, back: "/me/orders" })}>
                        결제하기
                      </Link>
                    ) : ["PAYMENT_CONFIRMING", "PAYMENT_REVIEW", "REFUND_PENDING", "REFUND_REVIEW"].includes(o.status) ? (
                      <Link className="fq-btn is-ghost is-sm" href={checkoutHref({ orderId: o.orderId, kind: o.orderType, title: orderTitle(o), amount: o.totalAmount, back: "/me/orders" })}>
                        상세 보기
                      </Link>
                    ) : (o.status === "PAID" || o.status === "REFUNDED") && (o.orderType === "DROP" || o.orderType === "TICKET") ? (
                      <Link className="fq-btn is-ghost is-sm" href={checkoutHref({ orderId: o.orderId, kind: o.orderType, title: orderTitle(o), amount: o.totalAmount, back: "/me/orders" })}>
                        상세 보기
                      </Link>
                    ) : (
                      <span className="fq-num fq-muted">{s.code}</span>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </>
  );
}
