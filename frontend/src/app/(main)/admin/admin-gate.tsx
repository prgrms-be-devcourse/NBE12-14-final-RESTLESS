"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import LoadError from "@/components/LoadError";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

/** 주최자, 관리자만 여는 운영 하위 화면의 공통 틀. 로그인 전이면 로그인으로 보낸다 */
export default function AdminGate({ lead, children }: { lead: React.ReactNode; children: React.ReactNode }) {
  const { profile, ready } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  useEffect(() => {
    if (ready && !profile) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [ready, profile, router, pathname]);
  if (!ready || !profile) return <div className="fq-skel" style={{ height: 300 }} />;
  if (profile.role === "USER") return <LoadError error={new ApiError(403, { code: "FORBIDDEN", message: "권한이 없어요." })} />;
  return (
    <>
      <Link href="/admin" className="fq-back">
        ← 운영 콘솔
      </Link>
      <p className="fq-lead">
        {lead} 지금 권한: <span className="fq-chip">{profile.role}</span>
      </p>
      {children}
    </>
  );
}
