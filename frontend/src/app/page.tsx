"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import Starfield from "@/components/Starfield";
import "./intro.css";

/** 이만큼 길게 누르면 워프가 끝나고 바로 홈으로 들어간다 */
const HOLD_MS = 1300;

export default function Intro() {
  const [warp, setWarp] = useState(false);
  const [progress, setProgress] = useState(0);
  const router = useRouter();
  const [stage, setStage] = useState<"space" | "flash">("space");
  const [speed, setSpeed] = useState(1);
  const holdStart = useRef(0);
  const raf = useRef(0);
  const btnRef = useRef<HTMLButtonElement>(null);

  const arrive = useCallback(() => {
    setWarp(false);
    setStage("flash");
    // 섬광이 화면을 덮는 동안 홈을 미리 불러 두었다가 넘어간다
    router.prefetch("/home");
    setTimeout(() => router.push("/home"), 650);
  }, [router]);

  const tick = useCallback(() => {
    const p = Math.min(1, (performance.now() - holdStart.current) / HOLD_MS);
    setProgress(p);
    if (p >= 1) {
      arrive();
      return;
    }
    raf.current = requestAnimationFrame(tick);
  }, [arrive]);

  const start = useCallback(() => {
    if (stage !== "space") return;
    setWarp(true);
    holdStart.current = performance.now();
    cancelAnimationFrame(raf.current);
    raf.current = requestAnimationFrame(tick);
  }, [stage, tick]);

  const stop = useCallback(() => {
    cancelAnimationFrame(raf.current);
    setWarp(false);
    setProgress(0);
  }, []);

  useEffect(() => () => cancelAnimationFrame(raf.current), []);
  useEffect(() => {
    if (stage === "space") btnRef.current?.focus({ preventScroll: true });
  }, [stage]);

  return (
    <main
      className={`fq-intro is-${stage}${warp ? " is-warp" : ""}`}
      style={{ "--p": progress } as React.CSSProperties}
      onPointerDown={(e) => {
        if (stage === "space" && (e.target as HTMLElement).tagName === "CANVAS") start();
      }}
      onPointerUp={stop}
      onPointerCancel={stop}
    >
      <div className="fq-intro-sky">
        <Starfield warp={warp} density={1.2} onSpeed={(m) => setSpeed(Math.round(m * 10) / 10)} />
      </div>

      <div className="fq-intro-hud" aria-hidden="true">
        <b>{warp ? "워프" : stage === "flash" ? "도착" : "순항"}</b>
        <span>×{speed.toFixed(1)}</span>
      </div>
      <Link className="fq-intro-skip" href="/home">
        바로 입장 →
      </Link>

      {(
        <section className="fq-intro-hero" aria-labelledby="fq-intro-title">
          <p className="fq-intro-kicker">ARTIST × FAN, LIVE SPACE</p>
          <h1 id="fq-intro-title" className="fq-intro-logo">
            FANQ
          </h1>
          <p className="fq-intro-sub">
            공연 예매, 한정판 드롭, 팬미팅, 경매를
            <br />
            한곳에서.
          </p>
          <button
            ref={btnRef}
            className="fq-intro-wp"
            type="button"
            onPointerDown={(e) => {
              e.stopPropagation();
              try {
                e.currentTarget.setPointerCapture(e.pointerId);
              } catch {
                /* 무시 */
              }
              start();
            }}
            onPointerUp={stop}
            onPointerCancel={stop}
            onKeyDown={(e) => {
              if ((e.key === " " || e.key === "Enter") && !e.repeat) {
                e.preventDefault();
                start();
              }
            }}
            onKeyUp={(e) => {
              if (e.key === " " || e.key === "Enter") stop();
            }}
          >
            <svg viewBox="0 0 44 44" aria-hidden="true">
              <circle cx="22" cy="22" r="19" className="trk" />
              <circle cx="22" cy="22" r="19" className="bar" pathLength={1} />
            </svg>
            길게 눌러 입장
          </button>
          <p className="fq-intro-hint" aria-hidden="true">
            버튼을 길게 누르면 입장해요
          </p>
        </section>
      )}

      <div className="fq-intro-flash" aria-hidden="true" />
    </main>
  );
}
