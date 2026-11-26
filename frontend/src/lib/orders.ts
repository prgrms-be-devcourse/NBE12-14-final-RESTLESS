import type { OrderKind, OrderStatus } from "./types";

/** 주문 상태 → 배지 문구, 색(fq-chip 톤) */
export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: string; code: string }> = {
  PENDING_PAYMENT: { label: "결제 대기", tone: "is-live", code: "PENDING" },
  PAYMENT_CONFIRMING: { label: "결제 승인 중", tone: "is-warn", code: "CONFIRMING" },
  PAYMENT_REVIEW: { label: "결제 확인 중", tone: "is-warn", code: "REVIEW" },
  PAID: { label: "결제 완료", tone: "is-mint", code: "PAID" },
  EXPIRED: { label: "만료", tone: "is-mute", code: "EXPIRED" },
  CANCELLED: { label: "취소", tone: "is-mute", code: "CANCELLED" },
  REFUND_PENDING: { label: "환불 처리 중", tone: "is-warn", code: "REFUND_PENDING" },
  REFUND_REVIEW: { label: "환불 확인 중", tone: "is-warn", code: "REFUND_REVIEW" },
  REFUNDED: { label: "환불 완료", tone: "is-info", code: "REFUNDED" },
};

export const ORDER_KIND: Record<OrderKind, { label: string; icon: string }> = {
  FAN_MEETING: { label: "팬미팅", icon: "" },
  TICKET: { label: "공연 티켓", icon: "" },
  DROP: { label: "한정판 드롭", icon: "" },
};

export function orderTitle(order: { lines: { itemName: string; optionName: string; quantity: number }[] }) {
  return order.lines.map((line) => `${line.itemName}${line.optionName ? `, ${line.optionName}` : ""}${line.quantity > 1 ? ` × ${line.quantity}` : ""}`).join(", ") || "주문";
}

/**
 * 결제 기한 경과로 볼 결제 오류. 기한 뒤는 결제 모듈이 PAYMENT_WINDOW_EXPIRED로,
 * 드롭의 기한과 같은 시각은 드롭이 PAYMENT_NOT_PAYABLE(details.reason = DROP_NOT_PAYABLE)로 거절한다.
 * 화면은 둘을 같은 기한 경과로 안내하고 주문 상태를 다시 읽는다 (2026-11-25 사용자 결정, #446)
 */
export function isPaymentDeadlineError(e: { code?: string; details?: Record<string, unknown> | null } | null | undefined): boolean {
  if (!e) return false;
  if (e.code === "PAYMENT_WINDOW_EXPIRED") return true;
  return e.code === "PAYMENT_NOT_PAYABLE" && e.details?.reason === "DROP_NOT_PAYABLE";
}

/** 공통 결제 화면 주소. 백엔드에서 주문을 못 읽어 올 때를 대비해 금액, 제목, 기한을 함께 넘긴다. expired는 서버가 결제 기한 경과로 거절했다는 뜻이다 */
export function checkoutHref(o: { orderId: number; kind: OrderKind; title: string; amount: number; paymentExpiresAt?: string | null; back?: string; eventId?: number; expired?: boolean }) {
  const q = new URLSearchParams({ kind: o.kind, title: o.title, amount: String(o.amount) });
  if (o.paymentExpiresAt) q.set("expires", o.paymentExpiresAt);
  if (o.expired) q.set("expired", "1");
  if (o.back) q.set("back", o.back);
  if (o.eventId !== undefined) q.set("eventId", String(o.eventId));
  return `/checkout/${o.orderId}?${q.toString()}`;
}
