// 드롭 구매 흐름에서 새로고침을 넘겨 기억해야 하는 것들.
// 탭 단위 sessionStorage에 회원, 드롭별로 둔다. 서버가 돌려준 대기 상태와 회원, SKU가 모두 맞을 때만 다시 쓴다.

export type Address = { recipient: string; phone: string; address: string };
export type ReservationBody = Address & { variantId: number };
/** 응답을 못 받은 재고 점유 요청. 같은 키와 같은 body로만 다시 보낸다 */
export type PendingReservation = { key: string; body: ReservationBody };
/**
 * 만들어진 주문. prepareKey는 이 재고 점유의 결제 준비에 쓰는 Idempotency-Key다.
 * 결제창을 닫았다 다시 열거나 새로고침해도 같은 키를 보내 서버의 같은 READY 시도를 받는다.
 */
export type DoneReservation = { reservationId: number; orderId: number; amount: number; expiresAt: string; variantId: number; prepareKey?: string };

export type SavedPurchase = {
  v: 1;
  memberId: number;
  dropId: number;
  variantId: number;
  /** 결제하기를 누를 때 확인한 배송지 */
  address: Address;
  pending?: PendingReservation | null;
  done?: DoneReservation | null;
};

const LIMIT = { recipient: 50, phone: 20, address: 255 } as const;
const PHONE = /^[0-9+\-\s]{8,20}$/;

export const EMPTY_ADDRESS: Address = { recipient: "", phone: "", address: "" };

export function trimAddress(a: Address): Address {
  return { recipient: a.recipient.trim(), phone: a.phone.trim(), address: a.address.trim() };
}

/** 서버 검증(NotBlank, 길이)과 같은 기준에 연락처 형식을 더한다. 문제없으면 null */
export function addressProblem(a: Address): string | null {
  const t = trimAddress(a);
  if (!t.recipient || !t.phone || !t.address) return "받는 분, 연락처, 주소를 모두 입력해 주세요.";
  if (t.recipient.length > LIMIT.recipient) return `받는 분은 ${LIMIT.recipient}자 이내로 입력해 주세요.`;
  if (!PHONE.test(t.phone)) return "연락처는 숫자와 하이픈으로 입력해 주세요. 예: 010-0000-0000";
  if (t.address.length > LIMIT.address) return `주소는 ${LIMIT.address}자 이내로 입력해 주세요.`;
  return null;
}

export const ADDRESS_LIMIT = LIMIT;

export function sameAddress(a: Address, b: Address) {
  const x = trimAddress(a);
  const y = trimAddress(b);
  return x.recipient === y.recipient && x.phone === y.phone && x.address === y.address;
}

export function defaultAddress(p: { defaultRecipient: string | null; defaultPhone: string | null; defaultAddress: string | null } | null): Address {
  return { recipient: p?.defaultRecipient ?? "", phone: p?.defaultPhone ?? "", address: p?.defaultAddress ?? "" };
}

const storageKey = (memberId: number, dropId: number) => `fanq.drop.purchase.${memberId}.${dropId}`;

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

function isAddress(a: unknown): a is Address {
  const x = a as Address | null;
  return !!x && typeof x.recipient === "string" && typeof x.phone === "string" && typeof x.address === "string";
}

export function loadPurchase(memberId: number, dropId: number, store: Storage | null = storage()): SavedPurchase | null {
  try {
    const raw = store?.getItem(storageKey(memberId, dropId));
    if (!raw) return null;
    const s = JSON.parse(raw) as SavedPurchase;
    // 키 이름만 믿지 않고 내용의 회원, 드롭도 맞춰 본다
    if (s?.v !== 1 || s.memberId !== memberId || s.dropId !== dropId || typeof s.variantId !== "number" || !isAddress(s.address)) return null;
    if (s.pending && (s.pending.body?.variantId !== s.variantId || typeof s.pending.key !== "string" || !isAddress(s.pending.body))) s.pending = null;
    if (s.done && (s.done.variantId !== s.variantId || typeof s.done.reservationId !== "number" || typeof s.done.orderId !== "number")) s.done = null;
    if (s.done && s.done.prepareKey !== undefined && typeof s.done.prepareKey !== "string") s.done.prepareKey = undefined;
    return s;
  } catch {
    return null;
  }
}

export function savePurchase(s: SavedPurchase, store: Storage | null = storage()) {
  try {
    store?.setItem(storageKey(s.memberId, s.dropId), JSON.stringify(s));
  } catch {
    /* 저장할 수 없으면 새로고침 뒤 배송지를 다시 확인받는다 */
  }
}

export function clearPurchase(memberId: number, dropId: number, store: Storage | null = storage()) {
  try {
    store?.removeItem(storageKey(memberId, dropId));
  } catch {
    /* 무시 */
  }
}

/**
 * 서버가 돌려준 대기 상태(회원, SKU)에 저장본을 쓸 수 있는지 판단한다.
 * 다른 회원이나 다른 SKU의 저장본이면 null이고, 화면은 배송지를 다시 확인받는다.
 */
export function restoreFor(saved: SavedPurchase | null, memberId: number, dropId: number, serverVariantId: number | undefined): SavedPurchase | null {
  if (!saved || serverVariantId == null) return null;
  if (saved.memberId !== memberId || saved.dropId !== dropId || saved.variantId !== serverVariantId) return null;
  return saved;
}

// ── 대기열 조회 간격과 재시도

/** 서버가 pollAfterMs를 주지 않을 때 쓰는 간격. 계약의 순번 구간표(1~20 1초, ~200 2초, ~1000 3초, 그 위 5초)와 같다 */
export function fallbackPollMs(position: number | null | undefined): number {
  const p = position ?? Infinity;
  if (p <= 20) return 1000;
  if (p <= 200) return 2000;
  if (p <= 1000) return 3000;
  return 5000;
}

/** 조회 1초 5회 제한(429)에 닿지 않게 하는 최소 간격 */
export const MIN_POLL_MS = 1000;

/** 다음 대기 조회까지 기다릴 시간. 서버가 준 pollAfterMs보다 자주 묻지 않는다 */
export function pollDelay(view: { position?: number | null; pollAfterMs?: number | null } | null | undefined): number {
  const server = view?.pollAfterMs;
  const base = typeof server === "number" && Number.isFinite(server) && server > 0 ? server : fallbackPollMs(view?.position);
  return Math.max(MIN_POLL_MS, base);
}

/**
 * 429, 503 DROP_QUEUE_BUSY 뒤 다시 보낼 때까지 기다릴 시간. Retry-After에 0~20%(최소 200ms) 지터를 더한다.
 * Retry-After가 없으면 fallbackMs를 쓴다. random은 테스트에서 바꿔 넣는다.
 */
export function retryDelay(retryAfterMs: number | null | undefined, fallbackMs: number, random: () => number = Math.random): number {
  const base = typeof retryAfterMs === "number" && retryAfterMs >= 0 ? retryAfterMs : fallbackMs;
  const jitter = Math.max(200, base * 0.2) * random();
  return Math.round(base + jitter);
}

/** 503 DROP_QUEUE_BUSY 진입을 자동으로 다시 보내는 횟수. 진입 Retry-After 5초 x 5회가 10초 5회 제한 창보다 길어 429에 닿지 않는다 */
export const ENTRY_BUSY_RETRIES = 3;

/**
 * 재고 점유(TX1) 결과를 모를 때 같은 키와 body로 자동으로 다시 보내는 횟수. 그래도 모르면 "결과 다시 확인" 버튼을 보여 준다.
 * 서버는 같은 키의 기존 점유를 그대로 돌려주므로 다시 보내도 주문이 두 번 생기지 않는다 (2026-11-25 사용자 결정, #446)
 */
export const RESERVE_AUTO_RETRIES = 2;

/** 재고 점유 자동 재확인 전 기다릴 시간. 1초, 2초에 지터를 더하고, 429면 Retry-After를 따른다 */
export function reserveRetryDelay(attempt: number, retryAfterMs: number | null | undefined, random: () => number = Math.random): number {
  return retryDelay(retryAfterMs, 1000 * (attempt + 1), random);
}

// ── 서버 응답 분류

export type EntryFailure = "busy" | "soldOut" | "notOnSale" | "rateLimited" | "unknown" | "rejected";

/** 대기열 진입 실패의 종류. busy는 같은 요청을 Retry-After 뒤 다시 보내도 안전하다 */
export function entryFailure(e: { status?: number; code?: string } | null | undefined): EntryFailure {
  const code = e?.code;
  const status = e?.status ?? 0;
  if (code === "DROP_QUEUE_BUSY") return "busy";
  if (code === "DROP_SOLD_OUT") return "soldOut";
  if (code === "DROP_NOT_ON_SALE") return "notOnSale";
  if (status === 429 || code === "TOO_MANY_REQUESTS") return "rateLimited";
  if (status === 0 || status >= 500) return "unknown";
  return "rejected";
}

export type ReserveFailure = "unknown" | "soldOut" | "admissionExpired" | "notOnSale" | "activeExists" | "invalid" | "rejected";

/**
 * 재고 점유 요청 실패의 종류. unknown은 결과를 모르는 것이라 같은 키와 body로만 다시 확인한다.
 * 서버는 같은 키를 다른 검사보다 먼저 찾으므로, unknown이 아닌 거절은 그 키로 만든 점유가 없다는 뜻이다.
 */
export function reserveFailure(e: { status?: number; code?: string } | null | undefined): ReserveFailure {
  const code = e?.code;
  const status = e?.status ?? 0;
  if (status === 0 || status >= 500 || status === 429) return "unknown";
  if (code === "DROP_SOLD_OUT") return "soldOut";
  if (code === "DROP_ADMISSION_REQUIRED") return "admissionExpired";
  if (code === "DROP_NOT_ON_SALE") return "notOnSale";
  if (code === "DROP_ACTIVE_PURCHASE_EXISTS") return "activeExists";
  if (code === "INVALID_REQUEST") return "invalid";
  return "rejected";
}

export type QueueAction = "wait" | "reserve" | "consumed" | "expired" | "unavailable" | "otherSku";

/**
 * 대기 조회 결과로 화면이 할 일. 이 구매가 고정한 SKU와 다른 대기가 보이면
 * 다른 창이 다른 옵션으로 다시 들어온 것이라 이 구매를 이어가지 않는다.
 */
export function queueAction(view: { status: string; variantId?: number | null }, lockedVariantId: number): QueueAction {
  if (view.variantId != null && view.variantId !== lockedVariantId) return "otherSku";
  switch (view.status) {
    case "ADMITTED": return "reserve";
    case "CONSUMED": return "consumed";
    case "EXPIRED": return "expired";
    case "UNAVAILABLE": return "unavailable";
    default: return "wait";
  }
}

// ── 진행 중 주문 찾기 (2026-11-26 사용자 결정, #453. #446의 진입 전 조회를 대체)

/**
 * 구매 슬롯을 잡고 있는 주문 상태. 이 상태의 드롭 주문이 있으면 재고 점유가 409 DROP_ACTIVE_PURCHASE_EXISTS다.
 * 화면은 그 409를 받았을 때 이 상태의 주문을 찾아 결제를 이어간다.
 */
export const ACTIVE_ORDER_STATUSES = ["PENDING_PAYMENT", "PAYMENT_CONFIRMING", "PAYMENT_REVIEW"] as const;

/** 진입 전에 살펴볼 내 주문 목록의 쪽 크기(계약 최대 100). 승인 중, 확인 중 주문은 오래 남을 수 있어 마지막 쪽까지 본다 */
export const ACTIVE_ORDER_PAGE_SIZE = 100;
/** 쪽을 넘기는 상한. 넘으면 끝까지 확인하지 못한 것으로 본다 */
export const ACTIVE_ORDER_MAX_PAGES = 50;

/**
 * 내 주문 한 쪽에서 이 드롭의 것일 수 있는 진행 중 주문을 최신순으로 고른다.
 * 목록에는 dropId가 없어서, 어느 드롭의 주문인지는 주문 상세의 payable과 재고 점유 조회(다른 드롭이면 404)로 가린다.
 */
export function activeDropOrderCandidates<T extends { orderId: number; orderType: string; status: string }>(items: T[] | null | undefined): T[] {
  return (items ?? []).filter((o) => o.orderType === "DROP" && (ACTIVE_ORDER_STATUSES as readonly string[]).includes(o.status));
}

/** 찾은 진행 중 주문으로 할 일. PENDING_PAYMENT는 그 주문으로 결제창을 열고, 승인 중이거나 확인 중이면 결제 확인 중을 보여 준다 */
export function activeOrderAction(status: string): "pay" | "checking" {
  return status === "PENDING_PAYMENT" ? "pay" : "checking";
}

type OrderRow = { orderId: number; orderType: string; status: string };
type OrderPayable = { status: string; payable: { payableType: string; payableId: number } | null; shipping: Address | null };
type ReservationRow = { reservationId: number; orderId: number; variantId: number; amount: number; expiresAt: string };

/** 이 드롭의 진행 중 주문을 끝까지 확인하지 못했다. 진입하지 않고 조회를 다시 시도하게 한다 */
export class ActiveOrderUnknown extends Error {}

/**
 * 내 주문 목록을 마지막 쪽까지 넘기며 이 드롭의 진행 중 주문을 찾는다. 찾으면 바로 멈춘다(드롭마다 진행 중 거래는 하나다).
 * 목록에 dropId가 없어 주문 상세의 payable(reservationId)로 이 드롭의 재고 점유를 조회해 가린다. 다른 드롭의 점유는 404다.
 * 그 밖의 조회 실패는 주문 유무를 모르는 것이라 ActiveOrderUnknown을 던진다.
 */
export async function findActiveDropOrder(io: {
  listPage: (page: number) => Promise<{ items: OrderRow[]; totalPages: number }>;
  detail: (orderId: number) => Promise<OrderPayable>;
  reservation: (reservationId: number) => Promise<ReservationRow>;
}): Promise<{ status: string; shipping: Address | null; reservation: ReservationRow } | null> {
  const unknown = (e: unknown) => (e instanceof ActiveOrderUnknown ? e : new ActiveOrderUnknown(e instanceof Error ? e.message : String(e)));
  try {
    for (let page = 0; ; page++) {
      if (page >= ACTIVE_ORDER_MAX_PAGES) throw new ActiveOrderUnknown("too many pages");
      const list = await io.listPage(page);
      for (const o of activeDropOrderCandidates(list.items)) {
        const d = await io.detail(o.orderId);
        if (d.payable?.payableType !== "DROP") continue;
        if (!(ACTIVE_ORDER_STATUSES as readonly string[]).includes(d.status)) continue; // 목록 뒤에 끝난 주문
        let r: ReservationRow;
        try {
          r = await io.reservation(d.payable.payableId);
        } catch (e) {
          if ((e as { status?: number } | null)?.status === 404) continue; // 다른 드롭의 주문
          throw e;
        }
        if (r.orderId !== o.orderId) continue;
        return { status: d.status, shipping: d.shipping, reservation: r };
      }
      if (page + 1 >= (list.totalPages ?? 0) || !list.items?.length) return null;
    }
  } catch (e) {
    throw unknown(e);
  }
}
