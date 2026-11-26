import SuccessView from "./view";

// 토스 successUrl 복귀: ?paymentType=&orderId=(pgOrderId)&paymentKey=&amount=
export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ orderId: string }>;
  searchParams: Promise<{ paymentKey?: string; orderId?: string; amount?: string }>;
}) {
  const { orderId } = await params;
  const sp = await searchParams;
  return <SuccessView orderId={Number(orderId)} paymentKey={sp.paymentKey} pgOrderId={sp.orderId} amount={sp.amount ? Number(sp.amount) : undefined} />;
}
