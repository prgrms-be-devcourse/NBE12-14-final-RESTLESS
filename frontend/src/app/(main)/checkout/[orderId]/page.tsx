import CheckoutView from "./view";

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ orderId: string }>;
  searchParams: Promise<{ kind?: string; title?: string; amount?: string; expires?: string; back?: string; eventId?: string; expired?: string }>;
}) {
  const { orderId } = await params;
  const sp = await searchParams;
  return (
    <CheckoutView
      orderId={Number(orderId)}
      hint={{
        kind: sp.kind,
        title: sp.title,
        amount: sp.amount ? Number(sp.amount) : undefined,
        expires: sp.expires,
        back: sp.back?.startsWith("/") ? sp.back : undefined,
        eventId: sp.eventId ? Number(sp.eventId) : undefined,
        expired: sp.expired === "1",
      }}
    />
  );
}
