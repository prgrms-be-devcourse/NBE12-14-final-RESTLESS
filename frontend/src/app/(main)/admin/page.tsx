"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import LineArt, { type LineArtKind } from "@/components/LineArt";
import LoadError from "@/components/LoadError";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

/** 운영 콘솔 입구. 도메인마다 화면 하나에서 만들기와 관리를 함께 한다 */
const DOMAINS: { href: string; kind: LineArtKind; title: string; desc: string }[] = [
  { href: "/admin/concerts", kind: "ticket", title: "공연", desc: "초안을 만들고 예매 오픈, 구역 가격, 회차를 정한 뒤 공개해요." },
  { href: "/admin/drops", kind: "box", title: "드롭", desc: "한정판 굿즈를 초안으로 만들고 공개하거나 삭제해요." },
  { href: "/admin/fan-meetings", kind: "card", title: "팬미팅", desc: "추첨 팬미팅을 만들고, 응모 전인 행사를 고치거나 취소해요." },
  { href: "/admin/auctions", kind: "chip", title: "경매", desc: "경매를 등록하고, 시작 전인 경매를 고치거나 삭제해요." },
];

export default function AdminPage() {
  const { profile, ready } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (ready && !profile) router.replace(`/login?next=${encodeURIComponent("/admin")}`);
  }, [ready, profile, router]);
  if (!ready || !profile) return <div className="fq-skel" style={{ height: 300 }} />;
  if (profile.role === "USER") return <LoadError error={new ApiError(403, { code: "FORBIDDEN", message: "권한이 없어요." })} />;
  return (
    <>
      <p className="fq-lead">
        주최자는 본인이 만든 항목만, 관리자는 모든 항목을 다뤄요. 지금 권한: <span className="fq-chip">{profile.role}</span>
      </p>
      <nav className="fq-grid" aria-label="운영 메뉴" style={{ marginTop: 22 }}>
        {DOMAINS.map((d) => (
          <Link key={d.href} href={d.href} className="fq-card is-pad">
            <LineArt kind={d.kind} size={56} />
            <h3 style={{ marginTop: 12 }}>{d.title}</h3>
            <p className="fq-muted" style={{ marginTop: 6 }}>
              {d.desc}
            </p>
          </Link>
        ))}
      </nav>
    </>
  );
}
