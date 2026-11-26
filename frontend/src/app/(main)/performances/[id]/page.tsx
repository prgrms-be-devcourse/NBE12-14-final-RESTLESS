import SeatsView from "./view";

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ max?: string; concert?: string }>;
}) {
  const { id } = await params;
  const sp = await searchParams;
  return <SeatsView id={Number(id)} max={Number(sp.max) || 4} concertId={sp.concert ?? null} />;
}
