"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import ChapterHud from "./chapter/ChapterHud";
import type { FinaleState } from "./finale/BoxFinale";
import Odometer from "./casino/Odometer";
import Poster from "./Poster";
import { chapterAt, chapterStart, mix, ramp, smooth } from "@/lib/chapters";
import { scrollToY } from "@/lib/smooth";
import "./Orbit.css";

// 마무리 3D 장면은 무겁다. 궤도 중반을 지난 뒤에 브라우저에서만 불러온다
const BoxFinale = dynamic(() => import("./finale/BoxFinale"), { ssr: false });

export type OrbitCard = {
  key: string;
  eyebrow: string;
  title: string;
  label: string;
  count: number | null;
  countLabel: string;
  headline: string;
  href: string;
  seed: number;
  word: string;
  /** 카드를 크게 볼 때 무대에 번지는 색 */
  world: string;
  /** 마무리 화면의 바로가기가 가는 목록 주소 */
  listHref: string;
};

const STEP = 90;
/** 장 길이 비율: 시작(Q 로고), 펼치기, 카드 네 장, 그리고 마무리 다섯 장(떠오름, 하나씩, 상자, 담기, 닫힘) */
const SPANS = [1.4, 0.8, 1.6, 1.6, 1.6, 1.6, 1.0, 2.2, 1.0, 1.9, 1.7];
const CARD0 = 2;
const FIN0 = 6;
const LAST = SPANS.length - 1;
const OBJECTS = ["TICKET", "LIGHTSTICK", "PHOTOCARD", "CHIP"];
const FIN_COPY = [
  { title: "Four Objects", lead: "티켓, 응원봉, 포토카드, 칩." },
  { title: "One by One", lead: "서비스별로 살펴보세요." },
  { title: "One Box", lead: "FANQ 박스" },
  { title: "Pack It", lead: "칸마다 하나씩 담아요." },
  { title: "Sealed", lead: "FANQ 하나로 모두 이용할 수 있어요." },
];
/** 카드를 크게 볼 때 궤도를 카메라 쪽으로 당기는 거리(px) */
const ZOOM_Z = 380;

/**
 * 홈 첫 화면. 장 7개로 나뉜 긴 스크롤 연출이다.
 * 1) 흩어진 빛 먼지가 모여 Q 로고가 되고 터진다. 2) 카드 네 장이 겹친 채 나타나 궤도로 펼쳐진다.
 * 3~6) 카드마다 앞으로 돌아와 화면 가득 커지고, 무대가 그 서비스 색으로 물들며 실제 숫자를 보여 준다.
 * 7~11) 카드가 3D 물건 네 개로 바뀌어 하나씩 돌고, 칸이 넷인 상자에 담기고, 뚜껑이 닫히며 FANQ 아이콘이 찍힌다.
 *    이 부분의 3D는 finale/BoxFinale이 그리고, 여기서는 장 위치만 알려 준다.
 * 3D는 CSS transform, 빛 먼지는 캔버스 하나로 그리고, 화면 밖이면 멈춘다.
 */
export default function Orbit({ cards }: { cards: OrbitCard[] }) {
  const rootRef = useRef<HTMLElement>(null);
  const ringRef = useRef<HTMLDivElement>(null);
  const cardRefs = useRef<(HTMLAnchorElement | null)[]>([]);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [chapter, setChapter] = useState(0);
  const chapterRef = useRef(0);
  const [sub, setSub] = useState(0);
  const subRef = useRef(0);
  const [armed, setArmed] = useState(false);
  const finale = useRef<FinaleState>({ index: -1, local: 0 });

  useEffect(() => {
    const root = rootRef.current!;
    const ring = ringRef.current!;
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const n = cards.length;

    let target = 0;
    let shown = 0;
    let tiltX = 0;
    let tiltY = 0;
    let mx = 0;
    let my = 0;
    let visible = true;
    let raf = 0;
    let last = 0;

    const dpr = Math.min(1.5, window.devicePixelRatio || 1);
    let w = 0;
    let h = 0;

    // 빛 먼지 한 알을 미리 그려 두고 찍어 낸다. 매 프레임 그라디언트를 만드는 것보다 훨씬 가볍다
    const COLORS = ["255,255,255", "226,220,255", "200,220,255"];
    const sprites = COLORS.map((c) => {
      const s = document.createElement("canvas");
      s.width = s.height = 32;
      const g = s.getContext("2d")!;
      const grd = g.createRadialGradient(16, 16, 0, 16, 16, 16);
      grd.addColorStop(0, `rgba(${c},1)`);
      grd.addColorStop(0.35, `rgba(${c},0.55)`);
      grd.addColorStop(1, `rgba(${c},0)`);
      g.fillStyle = grd;
      g.fillRect(0, 0, 32, 32);
      return s;
    });

    // Q 로고 모양에서 점을 뽑는다. 글꼴에 기대지 않고 고리와 꼬리를 직접 그린다
    const qPoints = (() => {
      const S = 200;
      const off = document.createElement("canvas");
      off.width = off.height = S;
      const g = off.getContext("2d")!;
      g.strokeStyle = "#fff";
      g.lineWidth = S * 0.13;
      g.beginPath();
      g.arc(S / 2, S * 0.47, S * 0.3, 0, Math.PI * 2);
      g.stroke();
      g.lineCap = "round";
      g.beginPath();
      g.moveTo(S * 0.6, S * 0.6);
      g.lineTo(S * 0.82, S * 0.86);
      g.stroke();
      const data = g.getImageData(0, 0, S, S).data;
      const pts: [number, number][] = [];
      for (let y = 0; y < S; y += 3) for (let x = 0; x < S; x += 3) if (data[(y * S + x) * 4 + 3] > 140) pts.push([x / S - 0.5, y / S - 0.5]);
      // 줄 순서로 뽑으면 한쪽에 몰리므로 섞어서 고르게 나눈다
      for (let i = pts.length - 1; i > 0; i--) {
        const j = (Math.random() * (i + 1)) | 0;
        [pts[i], pts[j]] = [pts[j], pts[i]];
      }
      return pts;
    })();

    const COUNT = reduce ? 160 : 460;
    const dust = Array.from({ length: COUNT }, (_, i) => {
      const q = qPoints[i % Math.max(1, qPoints.length)] ?? [0, 0];
      return {
        a: Math.random() * Math.PI * 2,
        r: 0.18 + Math.random() * 0.62,
        y: (Math.random() - 0.5) * 1.1,
        s: 0.0006 + Math.random() * 0.0022,
        z: Math.random(),
        c: (Math.random() * sprites.length) | 0,
        qx: q[0] + (Math.random() - 0.5) * 0.012,
        qy: q[1] + (Math.random() - 0.5) * 0.012,
      };
    });

    const resize = () => {
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();

    const readScroll = () => {
      const rect = root.getBoundingClientRect();
      const span = rect.height - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -rect.top / span)) : 0;
    };

    const frame = (now: number) => {
      raf = 0;
      const dt = Math.min(50, now - (last || now)) / 16.7;
      last = now;
      shown += (target - shown) * Math.min(1, 0.12 * dt);
      tiltX += (my * 6 - tiltX) * 0.06 * dt;
      tiltY += (mx * 8 - tiltY) * 0.06 * dt;

      const ch = chapterAt(shown, SPANS);
      const t = ch.local;

      // ── 장마다 값 정하기 ──
      let qForm = 0; // Q 로고로 모인 정도
      let appear = 0; // 카드가 보이는 정도
      let spread = 0; // 겹친 카드가 궤도로 펼쳐진 정도
      let rot = 0; // 궤도 회전(도)
      let zoom = 0; // 앞 카드를 크게 보는 정도
      let focus = -1; // 크게 보는 카드
      let fin = -1; // 마무리 장 번호
      if (ch.index === 0) {
        qForm = smooth(ramp(t, 0.05, 0.5)) * (1 - smooth(ramp(t, 0.78, 1)));
      } else if (ch.index === 1) {
        appear = smooth(ramp(t, 0, 0.35));
        spread = smooth(ramp(t, 0.3, 1));
        rot = -(1 - spread) * 30;
      } else if (ch.index < CARD0 + n) {
        const i = ch.index - CARD0;
        appear = 1;
        spread = 1;
        const turn = i === 0 ? 1 : smooth(ramp(t, 0, 0.22));
        rot = -(i - 1 + turn) * STEP;
        zoom = smooth(ramp(t, 0.22, 0.42)) * (1 - smooth(ramp(t, 0.8, 1)));
        focus = i;
      } else {
        // 마무리: 카드가 줄어들며 사라지고 3D 물건이 그 자리를 넘겨받는다
        fin = ch.index - FIN0;
        appear = fin === 0 ? 1 - smooth(ramp(t, 0, 0.3)) : 0;
        spread = 1;
        rot = -(n - 1) * STEP;
      }
      const idle = reduce ? 0 : Math.sin(now / 2600) * 2 * (1 - zoom);
      const shrink = fin >= 0 ? 0.5 + appear * 0.5 : 1;
      // 왼쪽 설명 카드가 보이는 정도. 카드 장에서는 확대 정도, 하나씩 장에서는 물건이 앞에 나온 정도
      let panel = zoom;
      let objIdx = 0;
      if (fin === 1) {
        objIdx = Math.min(n - 1, Math.floor(t * n));
        const u = t * n - objIdx;
        panel = smooth(ramp(u, 0.08, 0.3)) * (1 - smooth(ramp(u, 0.78, 0.95)));
        focus = objIdx;
      }
      finale.current = { index: fin, local: t };

      ring.style.transform = `translateZ(${zoom * (w >= 900 ? ZOOM_Z : 150)}px) scale(${shrink}) rotateX(${(-6 + tiltX) * (1 - zoom)}deg) rotateY(${rot + idle + tiltY * (1 - zoom)}deg)`;
      root.style.setProperty("--zoom", panel.toFixed(3));
      root.style.setProperty("--appear", appear.toFixed(3));
      root.style.setProperty("--end", (ch.index === LAST ? smooth(ramp(t, 0.66, 0.95)) : 0).toFixed(3));
      root.style.setProperty("--p", shown.toFixed(4));
      if (focus >= 0) root.style.setProperty("--world", cards[focus].world);
      if (fin === 1) focus = -1;

      const mid = (n - 1) / 2;
      cardRefs.current.forEach((el, i) => {
        if (!el) return;
        const angle = i * STEP * spread;
        const fan = 1 - spread;
        el.style.transform =
          `rotateY(${angle}deg) translateZ(calc(var(--radius) * ${spread})) ` +
          `translateX(${(i - mid) * 26 * fan}px) translateY(${Math.abs(i - mid) * 10 * fan + (1 - appear) * 120}px) rotateZ(${(i - mid) * 7 * fan}deg)`;
        const facing = Math.cos(((angle + rot) * Math.PI) / 180);
        const stackDim = i === 0 ? 1 : 1 - fan * 0.75;
        const others = focus >= 0 && i !== focus ? 1 - zoom : 1;
        const face = Math.max(0, facing) * stackDim * others * appear;
        el.style.setProperty("--face", String(face));
        // 보이지 않아야 할 때(시작 장, 다른 카드를 크게 볼 때)는 완전히 숨긴다
        el.style.opacity = String(appear * others * (0.22 + 0.78 * Math.max(0, facing) * stackDim));
        el.style.zIndex = String(Math.round((facing + 1) * 10) + (spread < 0.5 ? n - i : 0));
        el.tabIndex = facing > 0.7 && appear > 0.5 ? 0 : -1;
      });

      if (ch.index !== chapterRef.current) {
        chapterRef.current = ch.index;
        setChapter(ch.index);
        if (ch.index >= CARD0 + 1) setArmed(true);
      }
      if (objIdx !== subRef.current) {
        subRef.current = objIdx;
        setSub(objIdx);
      }

      // ── 빛 먼지 ──
      ctx.clearRect(0, 0, w, h);
      ctx.globalCompositeOperation = "lighter";
      const wide = w >= 900;
      const cx = w * (wide ? (ch.index > 0 ? 0.62 : 0.58) : 0.5);
      const cy = h * (!wide && ch.index > 0 ? 0.62 : 0.5);
      const qSize = Math.min(w, h) * 0.62;
      const spin = shown * Math.PI * 3;
      for (const d of dust) {
        if (!reduce) d.a += d.s * dt;
        const ang = d.a + spin * (0.3 + d.z * 0.7);
        const ox = cx + Math.cos(ang) * d.r * w * 0.55;
        const depth = Math.sin(ang);
        const oy = cy + d.y * h * 0.5 + depth * h * 0.06;
        const x = mix(ox, cx + d.qx * qSize, qForm);
        const y = mix(oy, h * 0.5 + d.qy * qSize, qForm);
        const size = mix(1.2 + d.z * 3 + (depth + 1) * 1, 3 + d.z * 2, qForm) * (1 + zoom * 0.5);
        ctx.globalAlpha = Math.min(1, mix(0.18 + d.z * 0.45 + depth * 0.12, 0.85, qForm));
        ctx.drawImage(sprites[d.c], x - size, y - size, size * 2, size * 2);
      }
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = "source-over";

      if (visible) raf = requestAnimationFrame(frame);
    };

    const kick = () => {
      if (!raf && visible) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      readScroll();
      kick();
    };
    const onMove = (e: PointerEvent) => {
      if (reduce || e.pointerType !== "mouse") return;
      mx = e.clientX / window.innerWidth - 0.5;
      my = e.clientY / window.innerHeight - 0.5;
    };
    const io = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      if (visible) {
        last = 0;
        kick();
      }
    });
    io.observe(root);
    readScroll();
    shown = target;
    kick();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", resize);
    window.addEventListener("pointermove", onMove, { passive: true });
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", resize);
      window.removeEventListener("pointermove", onMove);
    };
  }, [cards]);

  /** 목록에서 고르면 그 카드를 크게 보는 지점으로 이동한다 */
  const jump = (i: number) => {
    const root = rootRef.current;
    if (!root) return;
    const top = root.getBoundingClientRect().top + window.scrollY;
    const span = root.offsetHeight - window.innerHeight;
    const s = chapterStart(CARD0 + i, SPANS);
    const e = chapterStart(CARD0 + i + 1, SPANS);
    scrollToY(top + span * (s + (e - s) * 0.6));
  };

  const cardIdx = chapter >= CARD0 && chapter < FIN0 ? chapter - CARD0 : -1;
  const fin = chapter - FIN0;
  // 설명 카드에 띄울 도메인: 카드 장에서는 그 카드, 하나씩 장에서는 앞에 나온 물건
  const cur = cardIdx >= 0 ? cards[cardIdx] : fin === 1 ? cards[sub] : null;
  const curNo = cardIdx >= 0 ? cardIdx : sub;
  const title = chapter === 0 ? "FANQ" : chapter === 1 ? "Four Stages" : cardIdx >= 0 ? cards[cardIdx].eyebrow : FIN_COPY[fin].title;
  const lead =
    chapter === 0
      ? "공연 예매, 한정판 드롭, 팬미팅 추첨, 라이브 경매를 한곳에서."
      : chapter === 1
        ? "서비스마다 구매 방식이 달라요."
        : cardIdx >= 0
          ? cards[cardIdx].label
          : FIN_COPY[fin].lead;
  const names = ["시작", "펼치기", ...cards.map((c) => c.title), "떠오름", "하나씩", "상자", "담기", "닫힘"];

  return (
    <section ref={rootRef} className="fq-orbit" aria-label="FANQ 서비스">
      <div className="fq-orbit-stage">
        <div className="fq-orbit-world" aria-hidden="true" />
        <canvas ref={canvasRef} className="fq-orbit-dust" aria-hidden="true" />

        <div className="fq-orbit-copy">
          <span className="fq-orbit-eyebrow">FANQ LIVE SPACE</span>
          <h2 key={title} className="fq-orbit-title" data-text={title}>
            {title}
          </h2>
          <p key={lead} className="fq-orbit-lead">
            {lead}
          </p>
          <div className="fq-orbit-nav">
            <span className="fq-orbit-ask">WHAT ARE YOU LOOKING FOR?</span>
            <ul className="fq-orbit-menu">
              {cards.map((c, i) => (
                <li key={c.key}>
                  <button type="button" className={i === cardIdx ? "is-on" : undefined} aria-current={i === cardIdx ? "true" : undefined} onClick={() => jump(i)}>
                    <i aria-hidden="true">→</i>
                    {c.title}
                    {c.count !== null ? <em className="fq-num">{c.count}</em> : null}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* 카드를 크게 볼 때 왼쪽에 뜨는 설명 */}
        {cur ? (
          <div key={`${cur.key}-${fin === 1 ? "o" : "c"}`} className="fq-orbit-focus">
            <span className="fq-orbit-eyebrow">
              {fin === 1 ? `OBJECT ${String(curNo + 1).padStart(2, "0")} ${OBJECTS[curNo]}` : `${String(curNo + 1).padStart(2, "0")} ${cur.eyebrow.toUpperCase()}`}
            </span>
            <h3>{cur.title}</h3>
            <div className="fq-orbit-focus-count">
              {cur.count !== null ? <Odometer value={cur.count} suffix="" className="fq-num" /> : <b>곧</b>}
              <span>{cur.countLabel}</span>
            </div>
            <p>{cur.headline}</p>
            <Link href={cur.href} className="fq-orbit-focus-cta">
              입장하기 →
            </Link>
          </div>
        ) : null}

        <div className="fq-orbit-view">
          <div ref={ringRef} className="fq-orbit-ring">
            {cards.map((c, i) => (
              <Link
                key={c.key}
                href={c.href}
                ref={(el) => {
                  cardRefs.current[i] = el;
                }}
                className="fq-orbit-card"
                aria-label={`${c.title}, ${c.headline}`}
              >
                <Poster seed={c.seed} word={c.word} kicker={c.eyebrow} ratio="auto" className="fq-orbit-art" />
                <div className="fq-orbit-card-b">
                  <span className="fq-orbit-count">
                    {c.count !== null ? <b className="fq-num">{c.count}</b> : null}
                    {c.countLabel}
                  </span>
                  <strong>{c.headline}</strong>
                  <span className="fq-orbit-go">입장하기 →</span>
                </div>
              </Link>
            ))}
          </div>
        </div>

        {armed ? <BoxFinale state={finale} /> : null}

        {/* 상자가 닫힌 뒤의 마지막 화면 */}
        <div className={`fq-orbit-end${chapter === LAST ? " is-on" : ""}`}>
          <h3>SEE YOU ON STAGE</h3>
          <nav aria-label="무대 바로가기">
            {cards.map((c) => (
              <Link key={c.key} href={c.listHref} tabIndex={chapter === LAST ? 0 : -1} style={{ "--world": c.world } as React.CSSProperties}>
                {c.title}
                <i aria-hidden="true">→</i>
              </Link>
            ))}
          </nav>
        </div>

        <ChapterHud names={names} index={chapter} onSkip={() => {
            const root = rootRef.current;
            if (root) scrollToY(root.getBoundingClientRect().top + window.scrollY + root.offsetHeight - window.innerHeight);
          }} skipLabel="끝으로 ↓" />
      </div>
    </section>
  );
}
