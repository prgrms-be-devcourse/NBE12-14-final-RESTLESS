import { ANONYMOUS, loadTossPayments } from "@tosspayments/tosspayments-sdk";

const CLIENT_KEY = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;

export type TossCheckout = {
  /** 우리 서버가 만든 pgOrderId. 토스 orderId로 그대로 쓴다. */
  pgOrderId: string;
  /** 결제 준비 응답의 서버 확정 금액. */
  amount: number;
  orderName: string;
  /** 우리 주문 번호. 성공, 실패 복귀 경로에 쓴다. */
  orderId: number;
  /** 복귀 경로를 바꿔야 하는 특수 결제 대상. 생략하면 기존 checkout 경로를 쓴다. */
  successPath?: string;
  failPath?: string;
};

export type TossHandlers = {
  /** 사용자가 결제창에서 결제를 취소했다. */
  onCancel: () => void;
  /** 결제 요청 자체가 실패했다(결제창 오류, 파라미터 불일치 등). */
  onError: (e: unknown) => void;
  /**
   * 결제창을 열어도 되는지. SDK 로드와 렌더링 사이에 로그아웃하거나 화면을 떠났으면 false를 돌려준다.
   * false면 결제창을 띄우지 않거나 띄운 창을 닫고, 결제 요청도 보내지 않는다. 생략하면 늘 연다.
   */
  isCurrent?: () => boolean;
};

let current: { destroy: () => Promise<void> } | null = null;

/** 열려 있는 결제창을 닫는다. 결제창을 연 화면이 사라질 때 부른다. */
export async function closeTossPayment(): Promise<void> {
  const w = current;
  current = null;
  await w?.destroy().catch(() => undefined);
}

/**
 * 토스 결제창형 결제를 연다. 결제창을 띄운 뒤 바로 돌아온다. 띄웠으면 true, isCurrent가 막았으면 false.
 * 구매자가 결제하기를 누르면 토스가 브라우저를 successUrl로 이동시킨다. 승인은 그 화면이 서버에 요청한다.
 */
export async function openTossPayment(c: TossCheckout, handlers: TossHandlers): Promise<boolean> {
  if (!CLIENT_KEY) throw new Error("NEXT_PUBLIC_TOSS_CLIENT_KEY가 설정되지 않았습니다.");
  const ok = () => handlers.isCurrent?.() ?? true;
  await closeTossPayment();
  if (!ok()) return false;
  const toss = await loadTossPayments(CLIENT_KEY);
  if (!ok()) return false;
  const widgets = toss.widgets({ customerKey: ANONYMOUS });
  await widgets.setAmount({ currency: "KRW", value: c.amount });
  if (!ok()) return false;
  const orderName = c.orderName.slice(0, 100);
  const paymentWindow = await widgets.renderPaymentWindow({
    orderName,
    variantKey: { paymentMethod: "DEFAULT", agreement: "AGREEMENT" },
  });
  if (!ok()) {
    await paymentWindow.destroy().catch(() => undefined);
    return false;
  }
  current = paymentWindow;
  paymentWindow.on("paymentRequest", async () => {
    if (!ok()) {
      if (current === paymentWindow) await closeTossPayment();
      return;
    }
    try {
      await widgets.requestPayment({
        orderId: c.pgOrderId,
        orderName,
        successUrl: `${window.location.origin}${c.successPath ?? `/checkout/${c.orderId}/success`}`,
        failUrl: `${window.location.origin}${c.failPath ?? `/checkout/${c.orderId}/fail`}`,
      });
    } catch (e) {
      const code = (e as { code?: string }).code;
      if (code === "USER_CANCEL" || code === "PAY_PROCESS_CANCELED") handlers.onCancel();
      else handlers.onError(e);
    }
  });
  return true;
}
