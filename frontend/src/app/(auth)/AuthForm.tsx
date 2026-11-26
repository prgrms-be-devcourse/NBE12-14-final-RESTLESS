"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import Starfield from "@/components/Starfield";
import { useToast } from "@/components/Toast";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import "./auth.css";

export default function AuthForm({ mode }: { mode: "login" | "signup" }) {
  const { login, signup } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [busy, setBusy] = useState(false);
  const [warp, setWarp] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "login") await login(email, password);
      else await signup(email, password, nickname);
      setWarp(true);
      toast(mode === "login" ? "로그인했어요" : "가입이 완료됐어요", "ok");
      const requested = typeof window !== "undefined" ? new URLSearchParams(window.location.search).get("next") : null;
      const next = requested?.startsWith("/") && !requested.startsWith("//") ? requested : "/home";
      setTimeout(() => router.push(next), 700);
    } catch (err) {
      const ex = err as ApiError;
      const fields = ex.details?.fields as Record<string, string> | undefined;
      setError(fields ? Object.values(fields).join(", ") : ex.message);
      setBusy(false);
    }
  };

  return (
    <main className="fq-auth">
      <div className="fq-auth-sky">
        <Starfield warp={warp} />
      </div>
      <Link href="/" className="fq-auth-logo">
        FANQ
      </Link>
      <form className="fq-auth-card" onSubmit={submit}>
        <h1>{mode === "login" ? "로그인" : "회원가입"}</h1>
        <p>{mode === "login" ? "FANQ 계정으로 로그인하세요." : "계정 하나로 공연 예매, 드롭, 팬미팅, 경매를 모두 이용할 수 있어요."}</p>
        <div className="fq-field">
          <label htmlFor="email">이메일</label>
          <input id="email" className="fq-input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        {mode === "signup" ? (
          <div className="fq-field">
            <label htmlFor="nick">닉네임</label>
            <input id="nick" className="fq-input" required minLength={2} maxLength={30} value={nickname} onChange={(e) => setNickname(e.target.value)} />
          </div>
        ) : null}
        <div className="fq-field">
          <label htmlFor="pw">비밀번호</label>
          <input
            id="pw"
            className="fq-input"
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error ? (
          <p className="fq-auth-err" role="alert">
            {error}
          </p>
        ) : null}
        <button className="fq-btn is-grad is-block" disabled={busy}>
          {busy ? "처리 중…" : mode === "login" ? "로그인" : "가입하기"}
        </button>
        <p className="fq-auth-alt">
          {mode === "login" ? (
            <>
              계정이 없나요? <Link href="/signup">회원가입</Link>
            </>
          ) : (
            <>
              이미 계정이 있나요? <Link href="/login">로그인</Link>
            </>
          )}
          <br />
          <Link href="/home">로그인 없이 둘러보기</Link>
        </p>
      </form>
    </main>
  );
}
