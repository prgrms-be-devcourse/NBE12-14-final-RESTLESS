"use client";

import { useEffect, useRef, useState } from "react";
import ChapterHud from "@/components/chapter/ChapterHud";
import { chapterAt, mix, ramp, smooth } from "@/lib/chapters";
import "./lottery.css";

/** 팬미팅 상태 흐름 그대로: 응모 중, 응모 마감, 추첨 중, 당첨자 결제 중, 모집 완료 */
const CHAPTERS = [
  { name: "응모 중", title: "응모", lead: "응모 기간 동안 누구나 응모할 수 있어요." },
  { name: "응모 마감", title: "마감", lead: "응모가 마감되면 추첨 대상이 확정돼요." },
  { name: "추첨 중", title: "추첨", lead: "정해진 인원만큼 무작위로 당첨자를 뽑아요." },
  { name: "당첨자 결제 중", title: "당첨", lead: "당첨자는 결제 기한 안에 결제해야 참가가 확정돼요." },
  { name: "모집 완료", title: "모집 완료", lead: "당첨자 결제가 끝나면 모집이 완료돼요." },
];
const SPANS = [1, 1.1, 1.2, 1.2, 1.3];
const WINNERS = 44;

type Dot = { sx: number; sy: number; ph: number; d: number; ang: number; rad: number; fx: number; fy: number; wx: number; wy: number; win: number };

/**
 * 팬미팅 목록 앞의 긴 스크롤 연출. 응모자 한 명이 점 하나다.
 * 점들이 흩어져 있다가(응모) 글자로 모이고(마감), 원통처럼 돌며 섞이고(추첨),
 * 뽑힌 점만 가운데 줄지어 서고(결제), 마지막에 하트 모양으로 켜진다(모집 완료).
 * 숫자는 보여 주지 않는다. 실제 응모 인원이 아니라 흐름을 설명하는 그림이기 때문이다.
 */
export default function LotteryShow({ skipTo }: { skipTo: string }) {
  const rootRef = useRef<HTMLElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [chapter, setChapter] = useState(0);

  useEffect(() => {
    const root = rootRef.current!;
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    let w = 0;
    let h = 0;
    let dots: Dot[] = [];
    let grid: [number, number][] = [];
    let heart: [number, number][] = [];
    let raf = 0;
    let target = 0;
    let shown = 0;
    let visible = true;
    let cur = -1;

    /** 글자를 그려 놓고 색이 찬 자리만 점 좌표로 뽑는다 */
    const sampleWord = () => {
      const off = document.createElement("canvas");
      off.width = w;
      off.height = h;
      const g = off.getContext("2d")!;
      const lines = w > h * 1.1 ? ["LUCKY DRAW"] : ["LUCKY", "DRAW"];
      g.font = `900 100px "Pretendard Variable", Pretendard, -apple-system, "Helvetica Neue", Arial, sans-serif`;
      const widest = Math.max(...lines.map((l) => g.measureText(l).width));
      const size = Math.min((w * 0.82 * 100) / widest, (h * 0.5) / lines.length);
      g.font = `900 ${size}px "Pretendard Variable", Pretendard, -apple-system, "Helvetica Neue", Arial, sans-serif`;
      g.textAlign = "center";
      g.textBaseline = "middle";
      g.fillStyle = "#fff";
      lines.forEach((l, i) => g.fillText(l, w / 2, h * 0.44 + (i - (lines.length - 1) / 2) * size * 0.98));
      const data = g.getImageData(0, 0, w, h).data;
      const step = Math.max(3, Math.round(size / 26));
      const pts: [number, number][] = [];
      for (let y = 0; y < h; y += step) for (let x = 0; x < w; x += step) if (data[(y * w + x) * 4 + 3] > 130) pts.push([x, y]);
      for (let i = pts.length - 1; i > 0; i--) {
        const j = (Math.random() * (i + 1)) | 0;
        [pts[i], pts[j]] = [pts[j], pts[i]];
      }
      return pts;
    };

    const build = () => {
      // 무대가 빠졌거나 아직 크기가 없으면 점을 다시 만들지 않는다
      if (!canvas.clientWidth || !canvas.clientHeight) return;
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const word = sampleWord();
      const n = Math.max(WINNERS + 200, Math.min(reduce ? 500 : 1100, word.length));
      dots = Array.from({ length: n }, (_, i) => {
        const p = word[i % Math.max(1, word.length)] ?? [w / 2, h / 2];
        return {
          sx: Math.random(),
          sy: Math.random(),
          ph: Math.random() * Math.PI * 2,
          d: Math.random(),
          ang: Math.random() * Math.PI * 2,
          rad: 0.78 + Math.random() * 0.22,
          fx: Math.random(),
          fy: Math.random(),
          wx: p[0],
          wy: p[1],
          win: i < WINNERS ? i : -1,
        };
      });
      // 당첨자가 서는 줄: 가운데 정렬한 격자
      const cols = w > h ? 11 : 6;
      const gap = Math.min(46, (w * 0.7) / cols);
      const rows = Math.ceil(WINNERS / cols);
      grid = Array.from({ length: WINNERS }, (_, i) => [w / 2 + ((i % cols) - (cols - 1) / 2) * gap, h * 0.44 + (Math.floor(i / cols) - (rows - 1) / 2) * gap]);
      // 하트 윤곽선 위에 같은 간격으로 선다. 매개변수 간격이 아니라 실제 길이로 나눠야 뾰족한 곳에 몰리지 않는다
      const S = Math.min(w, h) * 0.017;
      const curve: [number, number][] = [];
      for (let i = 0; i <= 720; i++) {
        const t = (i / 720) * Math.PI * 2;
        const x = 16 * Math.pow(Math.sin(t), 3);
        const y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
        curve.push([w / 2 + x * S, h * 0.43 - y * S]);
      }
      const len = [0];
      for (let i = 1; i < curve.length; i++) len.push(len[i - 1] + Math.hypot(curve[i][0] - curve[i - 1][0], curve[i][1] - curve[i - 1][1]));
      let j = 0;
      heart = Array.from({ length: WINNERS }, (_, i) => {
        const at = (i / WINNERS) * len[len.length - 1];
        while (j < len.length - 2 && len[j + 1] < at) j++;
        return curve[j];
      });
    };

    const read = () => {
      const r = root.getBoundingClientRect();
      const span = root.offsetHeight - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -r.top / span)) : 0;
    };

    const P: [number, number] = [0, 0];
    /** 장 k에서 점 d가 있을 자리 */
    const place = (d: Dot, k: number, time: number, spin: number) => {
      if (k === 0) {
        P[0] = d.sx * w + Math.sin(time / 2300 + d.ph) * 14;
        P[1] = d.sy * h + Math.cos(time / 2700 + d.ph) * 14;
      } else if (k === 1) {
        P[0] = d.wx;
        P[1] = d.wy;
      } else if (k === 2) {
        // 옆에서 본 원통: 가로로는 넓게, 세로로는 납작하게 돈다
        const a = d.ang + time * 0.0005 + spin;
        const R = Math.min(w * 0.36, h * 0.52) * d.rad;
        P[0] = w / 2 + Math.cos(a) * R;
        P[1] = h * 0.44 + Math.sin(a) * R * 0.42 + (d.fy - 0.5) * h * 0.16;
      } else if (d.win >= 0) {
        const q = k === 3 ? grid[d.win] : heart[d.win];
        P[0] = q[0];
        P[1] = q[1];
      } else {
        // 뽑히지 않은 점은 바닥에 얇게 깔린다
        P[0] = d.fx * w;
        P[1] = h * (0.86 + d.fy * 0.1);
      }
    };

    const frame = (now: number) => {
      raf = 0;
      shown += (target - shown) * 0.12;
      if (Math.abs(target - shown) < 0.0003) shown = target;
      const ch = chapterAt(shown, SPANS);
      const k = ch.index;
      const t = ch.local;
      const time = reduce ? 0 : now;
      root.style.setProperty("--p", shown.toFixed(4));
      ctx.clearRect(0, 0, w, h);

      const last = k === SPANS.length - 1;
      const spin = k === 2 ? t * 5 : k > 2 ? 5 : 0;
      // 당첨 불빛: 결제 장에서는 테두리만, 모집 완료 장에서 하나씩 채워진다
      const pending = k === 3 ? smooth(ramp(t, 0.45, 0.8)) : k > 3 ? 1 : 0;
      const dim = k === 2 ? smooth(ramp(t, 0.6, 1)) : k >= 3 ? 1 : 0;
      let x0 = 0;
      let y0 = 0;
      for (const d of dots) {
        place(d, k, time, spin);
        x0 = P[0];
        y0 = P[1];
        let x = x0;
        let y = y0;
        if (!last) {
          place(d, k + 1, time, k + 1 === 2 ? 0 : 5);
          const tt = smooth(ramp(t, 0.42 + d.d * 0.22, 0.78 + d.d * 0.22));
          x = mix(x0, P[0], tt);
          y = mix(y0, P[1], tt);
          // 옮겨 가는 동안 살짝 휘어 날아간다
          y -= Math.sin(tt * Math.PI) * 26 * (d.d - 0.5);
        }
        if (d.win >= 0 && k >= 3) {
          const fill = k === 4 ? smooth(ramp(t, 0.3 + (d.win / WINNERS) * 0.4, 0.42 + (d.win / WINNERS) * 0.4)) : 0;
          const r = (3.2 + pending * 2.2) * Math.min(1, Math.max(0.5, Math.min(w, h) / 760));
          if (fill > 0) {
            ctx.fillStyle = `rgba(255,154,174,${0.28 * fill})`;
            ctx.beginPath();
            ctx.arc(x, y, r * 2.6, 0, Math.PI * 2);
            ctx.fill();
          }
          ctx.fillStyle = `rgba(255,${mix(255, 214, fill)},${mix(255, 224, fill)},${mix(0.9, 1, fill) * (fill > 0 ? 1 : 1 - pending * 0.75)})`;
          ctx.beginPath();
          ctx.arc(x, y, mix(1.4, r, Math.max(fill, 1 - pending)), 0, Math.PI * 2);
          ctx.fill();
          if (pending > 0 && fill < 1) {
            ctx.strokeStyle = `rgba(255,255,255,${0.85 * pending * (1 - fill)})`;
            ctx.lineWidth = 1.2;
            ctx.beginPath();
            ctx.arc(x, y, r, 0, Math.PI * 2);
            ctx.stroke();
          }
        } else {
          const tw = reduce ? 1 : 0.7 + Math.sin(time / 900 + d.ph) * 0.3;
          // 글자로 모였을 때 가장 또렷하고, 흩어져 있을 때는 조금 흐리다
          const sharp = k === 1 ? 1 - smooth(ramp(t, 0.6, 1)) * 0.3 : k === 0 ? smooth(ramp(t, 0.6, 1)) : 0;
          const a = Math.min(1, (0.5 + d.rad * 0.4 + sharp * 0.3) * tw * (1 - dim * 0.7));
          const sz = 1.9 + sharp * 0.9;
          ctx.fillStyle = `rgba(236,230,255,${a})`;
          ctx.fillRect(x - sz / 2, y - sz / 2, sz, sz);
        }
      }

      if (k !== cur) {
        cur = k;
        setChapter(k);
      }
      if (visible && (!reduce || shown !== target)) raf = requestAnimationFrame(frame);
    };
    const kick = () => {
      if (!raf && visible) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      read();
      kick();
    };
    const onResize = () => {
      build();
      onScroll();
    };
    const io = new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      if (visible) kick();
    });
    io.observe(root);
    build();
    read();
    shown = target;
    kick();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    // 글꼴이 늦게 들어오면 글자 모양이 달라지므로 한 번 더 만든다
    let alive = true;
    document.fonts?.ready.then(() => alive && onResize());
    return () => {
      alive = false;
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  const c = CHAPTERS[chapter];
  return (
    <section ref={rootRef} className="fq-lot" aria-label="팬미팅 추첨 흐름">
      <div className="fq-lot-stage">
        <canvas ref={canvasRef} aria-hidden="true" />
        <div className="fq-lot-copy">
          <span>FAN MEETING LUCKY DRAW</span>
          <h2 key={c.title}>{c.title}</h2>
          <p key={c.lead}>{c.lead}</p>
        </div>
        <ChapterHud names={CHAPTERS.map((x) => x.name)} index={chapter} skipTo={skipTo} />
      </div>
    </section>
  );
}
