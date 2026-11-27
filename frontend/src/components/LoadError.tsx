"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { ApiError } from "@/lib/api";

export default function LoadError({ error, backHref = "/home" }: { error: ApiError; backHref?: string }) {
  const pathname = usePathname();
  const router = useRouter();
  useEffect(() => {
    // 상세 화면 id는 쿼리에 있다. usePathname은 쿼리를 빼므로 돌아올 주소는 location에서 읽는다
    if (error.status === 401) router.replace(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
  }, [error.status, pathname, router]);
  if (error.status === 401) return <p className="fq-muted">로그인 화면으로 이동하고 있어요…</p>;
  const message = error.status === 403 ? "권한이 없어요." : error.status === 404 ? "찾을 수 없어요." : error.isUnavailable ? "서버에 연결할 수 없어요." : error.message;
  return <div className="fq-card is-pad fq-empty" role="alert"><LineArt kind="search" /><p>{message}</p><Link className="fq-btn is-ghost" href={backHref}>목록으로 돌아가기</Link></div>;
}
