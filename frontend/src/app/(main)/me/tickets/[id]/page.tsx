import TicketView from "./view";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <TicketView id={Number(id)} />;
}
