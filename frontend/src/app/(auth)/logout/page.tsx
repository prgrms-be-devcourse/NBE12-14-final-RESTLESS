"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import Starfield from "@/components/Starfield";
import { useAuth } from "@/lib/auth";
import "../auth.css";

type Phase = "pending" | "done" | "already";

/**
 * 주소로 들어와도 로그아웃되는 화면.
 * 로그아웃 API는 Access Token 없이도 Refresh 쿠키를 지우므로, 로그인 상태와 관계없이 한 번 부른다.
 * 첫 프로필 조회가 끝난 뒤(ready)에 불러야, 늦게 도착한 프로필이 로그아웃 상태를 덮어쓰지 않는다.
 */
export default function LogoutPage() {
  const { profile, ready, logout } = useAuth();
  const [phase, setPhase] = useState<Phase>("pending");
  const started = useRef(false);

  useEffect(() => {
    if (!ready || started.current) return;
    started.current = true;
    const wasSignedIn = profile !== null;
    logout().then(() => setPhase(wasSignedIn ? "done" : "already"));
  }, [ready, profile, logout]);

  return (
    <main className="fq-auth">
      <div className="fq-auth-sky">
        <Starfield warp={false} />
      </div>
      <Link href="/" className="fq-auth-logo">
        FANQ
      </Link>
      <section className="fq-auth-card" aria-live="polite" aria-busy={phase === "pending"}>
        <h1>{phase === "pending" ? "로그아웃 중…" : "로그아웃"}</h1>
        <p>
          {phase === "pending"
            ? "이 기기의 로그인 정보를 지우고 있어요."
            : phase === "done"
              ? "로그아웃했어요. 다음에 또 만나요."
              : "이미 로그아웃된 상태예요."}
        </p>
        {phase !== "pending" ? (
          <>
            <Link href="/home" className="fq-btn is-grad is-block">
              홈으로
            </Link>
            <p className="fq-auth-alt">
              <Link href="/login">다시 로그인</Link>
            </p>
          </>
        ) : null}
      </section>
    </main>
  );
}
