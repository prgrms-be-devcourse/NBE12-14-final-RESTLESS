import SuccessView from "./view";

export default async function Page({ params, searchParams }: {
  params: Promise<{ chargeId: string }>;
  searchParams: Promise<{ paymentKey?: string; orderId?: string; amount?: string }>;
}) {
  const { chargeId } = await params;
  const query = await searchParams;
  return <SuccessView chargeId={Number(chargeId)} paymentKey={query.paymentKey}
    pgOrderId={query.orderId} amount={query.amount === undefined ? undefined : Number(query.amount)} />;
}
