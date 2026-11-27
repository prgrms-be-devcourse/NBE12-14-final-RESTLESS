// 백엔드 DTO(record)를 그대로 옮긴 타입. Instant는 ISO 문자열로 온다.

export type ApiErrorBody = {
  code: string;
  message: string;
  details?: Record<string, unknown> | null;
  traceId?: string;
  timestamp?: string;
};

// ── 인증, 회원
export type LoginResponse = { accessToken: string; tokenType: string; expiresIn: number };
export type Role = "USER" | "ADMIN" | "ORGANIZER";
export type Profile = {
  memberId: number;
  email: string;
  nickname: string;
  role: Role;
  defaultRecipient: string | null;
  defaultPhone: string | null;
  defaultAddress: string | null;
};
export type WalletView = { balanceAmount: number; reservedAmount: number; availableAmount: number };
export type CreditCharge = {
  chargeId: number;
  amount: number;
  status: "READY" | "PAID";
  expiresAt: string;
  payable: { payableType: "CREDIT_CHARGE"; payableId: number };
};

// ── 공연, 티켓팅
export type ConcertDetail = {
  concertId: number;
  venueId: number;
  title: string;
  status: string;
  createdAt: string;
};
export type ConcertPage = {
  content: ConcertDetail[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type PerformanceInfo = {
  performanceId: number;
  concertId: number;
  venueId: number;
  layoutId: number;
  startsAt: string;
  bookingOpensAt: string;
  bookingClosesAt: string;
  maxTicketsPerUser: number;
  status: string;
};
export type SeatStatus = "AVAILABLE" | "HELD" | "SOLD";
export type TicketSeatState = { seatId: number; status: SeatStatus };
export type TicketSeatInfo = {
  seatId: number;
  venueSeatId: number;
  sectionLabel: string;
  rowLabel: string;
  seatNumber: string;
  posX: number;
  posY: number;
  grade: string;
  price: number;
  status: SeatStatus;
};
export type TicketReservationInfo = {
  reservationId: number;
  status: string;
  seatIds: number[];
  holdExpiresAt: string;
};
export type TicketReservationDetail = {
  reservationId: number;
  status: string;
  holdExpiresAt: string;
  performanceId: number;
  seats: { seatId: number; priceAmount: number }[];
  holdExpired: boolean;
  orderId: number | null;
  paymentExpiresAt: string | null;
  createdAt: string;
};
export type TicketBookingAvailability = {
  maximum: number;
  confirmed: number;
  reserved: number;
  remaining: number;
  activeReservation: TicketReservationDetail | null;
};
export type TicketCheckoutResponse = {
  reservationId: number;
  orderId: number;
  totalAmount: number;
  checkoutStartedAt: string;
  paymentExpiresAt: string;
};

export type IssuedTicket = {
  ticketId: number;
  ticketNo: string;
  reservationId: number;
  orderId: number;
  performanceId: number;
  concertId: number;
  concertTitle: string;
  startsAt: string;
  venueId: number;
  seat: { seatId: number; venueSeatId: number; grade: string; sectionLabel: string; rowLabel: string; seatNumber: string };
  priceAmount: number;
  status: string;
  issuedAt: string;
  usedAt: string | null;
  cancelledAt: string | null;
};
export type IssuedTicketPage = { content: IssuedTicket[]; page: number; size: number; totalElements: number; totalPages: number };

// ── 공연 주최 (/api/organizer/concerts/**)
export type OrganizerConcert = {
  concertId: number;
  venueId: number;
  layoutId: number;
  organizerId: number;
  title: string;
  status: string;
  createdAt: string;
  venueName: string;
  venueAddress: string;
  description: string | null;
  periodStartDate: string | null;
  periodEndDate: string | null;
  durationMinutes: number | null;
  published: boolean;
  publishedAt: string | null;
  pricingConfigured: boolean;
  /** 공연 단위 예매 오픈. 모든 회차가 이 값을 쓴다 */
  bookingOpensAt: string | null;
};
export type OrganizerConcertPage = { content: OrganizerConcert[]; page: number; size: number; totalElements: number; totalPages: number };
export type OrganizerLayout = { layoutId: number; name: string; status: string; sections: { sectionLabel: string; sellableSeats: number }[] };
export type SectionPrice = { sectionLabel: string; grade: string; price: number };
export type PerformanceSetup = {
  performance: PerformanceInfo;
  pricingStatus: "NOT_CONFIGURED" | "INCONSISTENT" | "CONFIGURED" | string;
  sellableSeatCount: number;
  configuredSeatCount: number;
  sections: { sectionLabel: string; seatCount: number; grade: string | null; price: number | null; uniform: boolean }[];
};

// ── 드롭
export type DropSaleStatus = "UPCOMING" | "ON_SALE" | "ENDED" | "SCHEDULED" | "OPEN" | "SOLD_OUT" | "CLOSED";
export type DropItem = {
  dropId: number;
  title: string;
  imageUrl: string;
  opensAt: string;
  closesAt: string;
  saleStatus: DropSaleStatus;
};
export type DropPage = { items: DropItem[] };
/** 운영 화면 목록. 주최자는 본인 드롭, 관리자는 전체 드롭(게시 전 포함) */
export type DropAdminStatus = "DRAFT" | "SCHEDULED" | "OPEN" | "CLOSED" | "CANCELLED";
export type DropManaged = {
  dropId: number;
  concertId: number;
  /** 시드 데이터 등 주최자 없이 만든 드롭은 null */
  organizerId: number | null;
  title: string;
  status: DropAdminStatus;
  opensAt: string;
  closesAt: string;
  createdAt: string;
};
export type DropManagedPage = { items: DropManaged[] };
export type DropVariant = {
  variantId: number;
  skuCode: string;
  optionName: string;
  priceAmount: number;
  soldOut: boolean;
};
export type DropDetail = DropItem & { description: string; variants: DropVariant[] };
export type QueueStatus = "WAITING" | "ADMITTED" | "CONSUMED" | "EXPIRED" | "CLOSED" | "UNAVAILABLE";
export type QueueView = { status: QueueStatus; variantId?: number; position?: number | null; admittedUntil?: string | null; pollAfterMs?: number | null };
export type DropReservationCreated = {
  reservationId: number;
  orderId: number;
  status: string;
  amount: number;
  expiresAt: string;
};
/** GET /api/drops/{dropId}/reservations/{reservationId} */
export type DropReservationDetail = DropReservationCreated & { variantId: number };

// ── 경매
export type AuctionStatus = "SCHEDULED" | "OPEN" | "CLOSED";
export type AuctionSummary = {
  auctionId: number;
  title: string;
  itemName: string;
  status: AuctionStatus;
  startingPriceAmount: number;
  startsAt: string;
  endsAt: string;
};
export type AuctionPage = {
  items: AuctionSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type HighestBid = { nickname: string; amount: number; acceptedAt: string };
export type AuctionDetail = {
  auctionId: number;
  title: string;
  itemName: string;
  itemDescription: string;
  sellerNickname: string;
  status: AuctionStatus;
  startingPriceAmount: number;
  minimumIncrementAmount: number;
  highestBid: HighestBid | null;
  minimumBidAmount: number;
  startsAt: string;
  endsAt: string;
  extensionDurationSeconds: number;
  revision: number;
  serverTime: string;
};
export type AuctionSnapshot = {
  auctionId: number;
  status: AuctionStatus;
  revision: number;
  highestBid: HighestBid | null;
  minimumBidAmount: number;
  endsAt: string;
  result?: { type: string; winnerNickname?: string; amount?: number } | null;
};
export type AuctionResult = {
  auctionId: number;
  type: "SOLD" | "UNSOLD";
  winnerNickname?: string;
  amount?: number;
  finalizedAt: string;
};
export type MyBid = {
  auctionId: number;
  title: string;
  itemName: string;
  auctionStatus: AuctionStatus;
  endsAt: string;
  bidId: number;
  amount: number;
  status: "HIGHEST" | "OUTBID" | "WON";
  acceptedAt: string;
};
export type MyBidPage = { items: MyBid[]; page: number; size: number; totalElements: number; totalPages: number };
export type BidResult = {
  bidId: number;
  auctionId: number;
  amount: number;
  status: string;
  acceptedAt: string;
  endsAt: string;
};

// ── 팬미팅
export type FanMeetingStatus =
  | "SCHEDULED"
  | "CANCELLED"
  | "OPEN"
  | "CLOSED"
  | "DRAWING"
  | "PAYMENT_IN_PROGRESS"
  | "RECRUITMENT_COMPLETED";
export type FanMeetingDetail = {
  eventId: number;
  title: string;
  status: FanMeetingStatus;
  entryOpensAt: string | null;
  entryClosesAt: string | null;
  meetingAt: string;
  winnerLimit: number;
  reserveLimit: number;
  feeAmount: number;
};
/** GET /api/fan-meetings 목록 항목. 상세와 달리 예비 인원이 없다 */
export type FanMeetingSummary = Omit<FanMeetingDetail, "reserveLimit">;
export type FanMeetingPage = { items: FanMeetingSummary[]; page: number; size: number; totalElements: number; totalPages: number };
export type MyApplication = { status: "APPLIED" | "WITHDRAWN" | "NONE" | string; appliedAt: string | null };
export type MyFanMeetingResult = {
  result: "NOT_APPLIED" | "PENDING" | "WINNER" | "RESERVE" | "NOT_SELECTED" | string;
  selectionStatus: string | null;
  reserveRank: number | null;
  orderId: number | null;
  paymentDeadline: string | null;
};
export type MyFanMeetingOrder = { orderId: number; status: string; amount: number; issuedAt: string; paymentDeadline: string; paidAt: string | null; declinable: boolean };

// ── 주문, 결제 (팬미팅, 티켓, 드롭 공통)
export type OrderStatus = "PENDING_PAYMENT" | "PAYMENT_CONFIRMING" | "PAYMENT_REVIEW" | "PAID" | "EXPIRED" | "CANCELLED" | "REFUND_PENDING" | "REFUND_REVIEW" | "REFUNDED";
export type OrderType = "TICKET" | "DROP";
export type OrderKind = OrderType | "FAN_MEETING";
export type OrderLine = { itemType: string; itemName: string; optionName: string; unitPrice: number; quantity: number };
export type OrderSummary = {
  orderId: number;
  orderType: OrderType;
  status: OrderStatus;
  totalAmount: number;
  createdAt: string;
  lines: OrderLine[];
};
export type OrderPage = { items: OrderSummary[]; page: number; size: number; totalElements: number; totalPages: number };
export type OrderDetail = OrderSummary & {
  shipping: { recipient: string; phone: string; address: string } | null;
  payable: { payableType: string; payableId: number } | null;
  payableExpiresAt: string | null;
  payment: { paymentId: number; status: string } | null;
};
export type PreparedPayment = { paymentId: number; pgOrderId: string; amount: number; orderName: string; expiresAt: string | null; status: string; created: boolean };
export type ConfirmedPayment = { paymentId: number; status: string };
export type PaymentDetail = { paymentId: number; payableType: string; payableId: number; pgOrderId: string; status: string; amount: number; approvalStartedAt: string | null; approvedAt: string | null; failureCode: string | null; refund: { refundId: number; kind: string; status: string; requestedAt: string; completedAt: string | null } | null };
export type RefundResult = { refundId: number; status: "REQUESTED" | "COMPLETED" | "UNKNOWN" | "FAILED" };
