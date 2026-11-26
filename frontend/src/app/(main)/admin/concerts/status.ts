import type { OrganizerConcert } from "@/lib/types";

/** 주최자 화면의 공연 단계. 공개 여부와 가격 설정으로 나눈다 */
export function concertStage(c: OrganizerConcert) {
  if (c.published) return { label: "공개됨", tone: "is-mint" };
  if (c.pricingConfigured) return { label: "가격 설정 완료", tone: "" };
  return { label: "초안", tone: "is-mute" };
}

export const PRICING_STATUS: Record<string, { label: string; tone: string }> = {
  CONFIGURED: { label: "좌석 준비 완료", tone: "is-mint" },
  NOT_CONFIGURED: { label: "좌석 미생성", tone: "is-warn" },
  INCONSISTENT: { label: "좌석 불일치", tone: "is-warn" },
};
