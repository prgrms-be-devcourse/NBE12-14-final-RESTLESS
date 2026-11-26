import FailView from "./view";

// 토스 failUrl 복귀: ?code=&message=&orderId=
export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ orderId: string }>;
  searchParams: Promise<{ code?: string; message?: string }>;
}) {
  const { orderId } = await params;
  const sp = await searchParams;
  return <FailView orderId={Number(orderId)} code={sp.code} message={sp.message} />;
}
