/** 발급 티켓 상태. 백엔드 Ticket.status 문자열을 그대로 받는다 */
export const TICKET_STATUS: Record<string, { label: string; tone: string }> = {
  ISSUED: { label: "사용 가능", tone: "is-mint" },
  USED: { label: "사용 완료", tone: "is-mute" },
  CANCELLED: { label: "취소됨", tone: "is-mute" },
};

export const ticketStatus = (s: string) => TICKET_STATUS[s] ?? { label: s, tone: "" };

export const seatText = (seat: { sectionLabel: string; rowLabel: string; seatNumber: string }) =>
  `${seat.sectionLabel.endsWith("구역") ? seat.sectionLabel : `${seat.sectionLabel}구역`} ${seat.rowLabel}열 ${seat.seatNumber}번`;
