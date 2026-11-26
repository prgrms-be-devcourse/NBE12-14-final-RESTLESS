import FailView from "./view";

export default async function Page({ params, searchParams }: {
  params: Promise<{ chargeId: string }>;
  searchParams: Promise<{ code?: string; message?: string }>;
}) {
  const { chargeId } = await params;
  const query = await searchParams;
  return <FailView chargeId={Number(chargeId)} code={query.code} message={query.message} />;
}
