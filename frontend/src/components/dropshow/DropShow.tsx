"use client";

import { useEffect, useRef, useState } from "react";
import ChapterHud from "@/components/chapter/ChapterHud";
import { chapterAt, mix, ramp, smooth } from "@/lib/chapters";
import "./dropshow.css";

/** 드롭 구매 흐름 그대로: 오픈, 대기열, 입장, 재고 확보, 결제 */
const CHAPTERS = [
  { name: "오픈", title: "오픈", lead: "정해진 시각에 판매가 시작돼요." },
  { name: "줄 서기", title: "줄 서기", lead: "옵션마다 줄이 따로 있어요. 새로고침해도 내 순번은 그대로예요." },
  { name: "입장", title: "입장", lead: "차례가 오면 문이 열리고, 5분 동안 살 수 있는 자격이 생겨요." },
  { name: "재고 확보", title: "재고 확보", lead: "재고를 먼저 확보한 순서대로 구매해요. 준비된 수량보다 많이 팔리지 않아요." },
  { name: "결제", title: "결제", lead: "확보한 재고는 결제 기한 안에 결제하면 구매가 확정돼요." },
];
const SPANS = [1, 1.5, 1, 1.5, 1.1];
/** 줄에 선 사람 수(점 개수)와 내 순서 */
const QUEUE = 150;
const ME = 112;
/** 재고 칸 수와 내가 잡는 칸 */
const STOCK = 30;
const MY_CELL = 21;

/**
 * 드롭 목록의 긴 스크롤 연출. 점 하나가 팬 한 명이다.
 * 위에서 쏟아진 점들이 구불구불한 줄을 서고, 문으로 하나씩 들어가고, 재고 칸을 하나씩 채운다.
 * 분홍 점이 "나"다. 숫자와 줄 길이는 흐름을 보여 주는 예시다.
 */
export default function DropShow({ skipTo }: { skipTo: string }) {
  const rootRef = useRef<HTMLElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const clockRef = useRef<HTMLElement>(null);
  const posRef = useRef<HTMLElement>(null);
  const leftRef = useRef<HTMLElement>(null);
  const timerRef = useRef<HTMLElement>(null);
  const [chapter, setChapter] = useState(0);

  useEffect(() => {
    const root = rootRef.current!;
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    let w = 0;
    let h = 0;
    let wide = true;
    let raf = 0;
    let target = 0;
    let shown = 0;
    let visible = true;
    let cur = -1;

    // 줄: 문에서 거꾸로 거슬러 올라가는 구불구불한 길. 길 위 거리로 점 위치를 정한다
    let path: [number, number][] = [];
    let cum: number[] = [];
    let gate: [number, number] = [0, 0];
    let spacing = 10;
    let cells: [number, number][] = [];
    let cell = 20;
    const spawn = Array.from({ length: QUEUE }, () => [Math.random(), Math.random() * 0.4 + 0.1, Math.random()]);

    const build = () => {
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      wide = w > h;
      gate = wide ? [w * 0.7, h * 0.46] : [w * 0.5, h * 0.42];
      // 문에서 시작해 왼쪽(넓은 화면) 또는 아래쪽(세로 화면)으로 지그재그
      const pts: [number, number][] = [gate];
      if (wide) {
        const rows = 5;
        const x0 = w * 0.12;
        const x1 = w * 0.62;
        const y0 = h * 0.24;
        const y1 = h * 0.7;
        pts.push([x1, gate[1]]);
        for (let r = 0; r < rows; r++) {
          const y = mix(y0, y1, r / (rows - 1));
          const right = r % 2 === 0;
          pts.push([right ? x1 : x0, y], [right ? x0 : x1, y]);
        }
      } else {
        const rows = 6;
        const x0 = w * 0.12;
        const x1 = w * 0.88;
        const y0 = h * 0.52;
        const y1 = h * 0.8;
        pts.push([gate[0], y0 - 14]);
        for (let r = 0; r < rows; r++) {
          const y = mix(y0, y1, r / (rows - 1));
          const right = r % 2 === 0;
          pts.push([right ? x1 : x0, y], [right ? x0 : x1, y]);
        }
      }
      path = pts;
      cum = [0];
      for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + Math.hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1]));
      spacing = cum[cum.length - 1] / (QUEUE + 4);
      // 재고 칸: 6 x 5
      const cols = 6;
      const rowsN = Math.ceil(STOCK / cols);
      cell = Math.min(wide ? h * 0.075 : w * 0.11, 54);
      const gx = wide ? w * 0.62 : w * 0.5;
      const gy = wide ? h * 0.46 : h * 0.56;
      cells = Array.from({ length: STOCK }, (_, i) => [gx + ((i % cols) - (cols - 1) / 2) * cell * 1.25, gy + (Math.floor(i / cols) - (rowsN - 1) / 2) * cell * 1.25]);
    };

    /** 문에서 거리 d만큼 떨어진 줄 위의 점 */
    const along = (d: number): [number, number] => {
      if (d <= 0) return [gate[0] + (wide ? -d : 0), gate[1] + (wide ? 0 : d)];
      const total = cum[cum.length - 1];
      const x = Math.min(d, total);
      let i = 1;
      while (i < cum.length - 1 && cum[i] < x) i++;
      const f = (x - cum[i - 1]) / Math.max(1e-6, cum[i] - cum[i - 1]);
      return [mix(path[i - 1][0], path[i][0], f), mix(path[i - 1][1], path[i][1], f)];
    };

    const read = () => {
      const r = root.getBoundingClientRect();
      const span = root.offsetHeight - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -r.top / span)) : 0;
    };

    const dot = (x: number, y: number, r: number, color: string) => {
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
    };
    const roundRect = (x: number, y: number, s: number, rad: number) => {
      ctx.beginPath();
      ctx.roundRect(x - s / 2, y - s / 2, s, s, rad);
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

      // ── 장마다 값 ──
      const arrive = k === 0 ? smooth(ramp(t, 0.35, 1)) : 1; // 점이 줄로 내려앉은 정도
      const advance = k === 0 ? 0 : k === 1 ? smooth(ramp(t, 0.05, 0.95)) * ME * spacing : ME * spacing; // 줄이 앞으로 간 거리
      const gateOpen = k === 2 ? smooth(ramp(t, 0.1, 0.45)) : k > 2 ? 1 : k === 1 ? smooth(ramp(t, 0.9, 1)) * 0.3 : 0;
      const meIn = k === 2 ? smooth(ramp(t, 0.35, 0.7)) : k > 2 ? 1 : 0;
      const queueFade = k >= 3 ? 1 - smooth(ramp(t, 0, 0.3)) : 1;
      const gridIn = k === 3 ? smooth(ramp(t, 0.05, 0.3)) : k > 3 ? 1 : 0;
      const claim = k === 3 ? ramp(t, 0.2, 0.9) : k > 3 ? 1 : 0; // 재고가 채워진 비율
      const finale = k === 4 ? smooth(ramp(t, 0.05, 0.6)) : 0;

      // 문: 두 기둥 사이로 빛이 새어 나온다
      if (queueFade > 0.01) {
        const [gx, gy] = gate;
        const gh = wide ? h * 0.16 : w * 0.16;
        ctx.save();
        // 카운트다운이 끝나고 점이 내려앉을 때 문이 함께 나타난다
        ctx.globalAlpha = queueFade * (k === 0 ? smooth(ramp(t, 0.55, 0.9)) : 1);
        if (gateOpen > 0) {
          const grd = wide ? ctx.createLinearGradient(gx, 0, gx + w * 0.25, 0) : ctx.createLinearGradient(0, gy, 0, gy - h * 0.2);
          grd.addColorStop(0, `rgba(255,236,214,${0.35 * gateOpen})`);
          grd.addColorStop(1, "rgba(255,236,214,0)");
          ctx.fillStyle = grd;
          if (wide) ctx.fillRect(gx, gy - gh * 0.5 * gateOpen, w * 0.25, gh * gateOpen);
          else ctx.fillRect(gx - gh * 0.5 * gateOpen, gy - h * 0.2, gh * gateOpen, h * 0.2);
        }
        ctx.strokeStyle = "rgba(244,240,232,0.75)";
        ctx.lineWidth = 2;
        ctx.beginPath();
        if (wide) {
          ctx.moveTo(gx, gy - gh / 2 - 10);
          ctx.lineTo(gx, gy - gh / 2 + (gh / 2) * (1 - gateOpen) * 0.9);
          ctx.moveTo(gx, gy + gh / 2 + 10);
          ctx.lineTo(gx, gy + gh / 2 - (gh / 2) * (1 - gateOpen) * 0.9);
        } else {
          ctx.moveTo(gx - gh / 2 - 10, gy);
          ctx.lineTo(gx - gh / 2 + (gh / 2) * (1 - gateOpen) * 0.9, gy);
          ctx.moveTo(gx + gh / 2 + 10, gy);
          ctx.lineTo(gx + gh / 2 - (gh / 2) * (1 - gateOpen) * 0.9, gy);
        }
        ctx.stroke();
        ctx.restore();
      }

      // 재고 칸
      if (gridIn > 0.01) {
        ctx.save();
        ctx.globalAlpha = gridIn * (1 - finale * 0.85);
        cells.forEach(([cx, cy], i) => {
          const filled = claim * STOCK > i + 0.5;
          roundRect(cx, cy, cell, 6);
          ctx.strokeStyle = "rgba(244,240,232,0.28)";
          ctx.lineWidth = 1;
          ctx.stroke();
          if (filled && i !== MY_CELL) {
            roundRect(cx, cy, cell - 6, 4);
            ctx.fillStyle = "rgba(236,230,255,0.82)";
            ctx.fill();
          }
        });
        ctx.restore();
      }

      // 줄 선 점들
      let meX = 0;
      let meY = 0;
      for (let q = 0; q < QUEUE; q++) {
        const isMe = q === ME;
        const d = q * spacing + spacing * 2 - advance;
        let [x, y] = along(d);
        // 문을 지난 점은 안쪽으로 흘러가다 사라진다
        let a = 0.78;
        if (d < 0) {
          a = Math.max(0, 0.78 + d / (spacing * 12));
        }
        // 오픈 장: 위에서 쏟아져 줄로 내려앉는다
        const s = spawn[q];
        const land = smooth(ramp(arrive, s[2] * 0.5, s[2] * 0.5 + 0.5));
        x = mix(s[0] * w, x, land);
        y = mix(-20 - s[1] * h * 0.4, y, land);
        // 줄 위에서 조금씩 들썩인다
        if (!reduce && d > 0) y += Math.sin(time / 500 + q * 0.7) * 1.2;
        if (isMe) {
          // 문을 지나 안쪽 대기 자리로, 그 뒤엔 내 재고 칸으로
          const inside: [number, number] = wide ? [gate[0] + w * 0.08, gate[1]] : [gate[0], gate[1] - h * 0.06];
          x = mix(x, inside[0], meIn);
          y = mix(y, inside[1], meIn);
          if (k >= 3) {
            const toCell = smooth(ramp(claim, MY_CELL / STOCK - 0.08, MY_CELL / STOCK));
            x = mix(x, cells[MY_CELL][0], toCell);
            y = mix(y, cells[MY_CELL][1], toCell);
          }
          meX = x;
          meY = y;
          continue;
        }
        if (k >= 3) a *= queueFade;
        if (a > 0.01) dot(x, y, 2.2, `rgba(236,230,255,${a})`);
      }

      // 나: 분홍 점과 5분 타이머 고리
      const myCellFilled = claim * STOCK > MY_CELL;
      if (k >= 3 && myCellFilled) {
        const grow = 1 + finale * 2.6;
        const cx = mix(cells[MY_CELL][0], wide ? w * 0.62 : w * 0.5, finale);
        const cy = mix(cells[MY_CELL][1], wide ? h * 0.44 : h * 0.5, finale);
        ctx.save();
        ctx.shadowColor = "rgba(255,90,110,0.8)";
        ctx.shadowBlur = 24;
        roundRect(cx, cy, (cell - 6) * grow, 4 * grow);
        ctx.fillStyle = "#ff5a6e";
        ctx.fill();
        ctx.restore();
        if (finale > 0.4) {
          ctx.fillStyle = `rgba(255,255,255,${smooth(ramp(finale, 0.4, 1))})`;
          ctx.font = `800 ${Math.round(cell * 0.42 * grow * 0.5)}px "Pretendard Variable", Pretendard, -apple-system, sans-serif`;
          ctx.textAlign = "center";
          ctx.textBaseline = "middle";
          ctx.fillText("MINE", cx, cy);
        }
      } else {
        dot(meX, meY, 5, "#ff5a6e");
        ctx.strokeStyle = "rgba(255,90,110,0.45)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(meX, meY, 10 + (reduce ? 0 : Math.sin(time / 300) * 2), 0, Math.PI * 2);
        ctx.stroke();
        if (k === 2 && meIn > 0.5) {
          const left = 1 - ramp(t, 0.55, 1) * 0.15;
          ctx.strokeStyle = "rgba(255,255,255,0.9)";
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.arc(meX, meY, 22, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * left);
          ctx.stroke();
        }
      }

      // ── 글자 ──
      if (clockRef.current) {
        const sec = Math.max(0, 3 - Math.floor(ramp(t, 0, 0.45) * 4));
        clockRef.current.textContent = k === 0 && t < 0.45 ? `00:00:0${sec}` : "OPEN";
      }
      if (posRef.current) {
        const remain = Math.max(0, Math.round(((ME * spacing + spacing * 2 - advance) / spacing) * 11.3));
        posRef.current.textContent = remain.toLocaleString("ko-KR");
      }
      if (timerRef.current) {
        const s = Math.round(300 - (k === 2 ? ramp(t, 0.55, 1) : k > 2 ? 1 : 0) * 45);
        timerRef.current.textContent = `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
      }
      if (leftRef.current) leftRef.current.textContent = String(Math.max(0, STOCK - Math.round(claim * STOCK)));
      root.style.setProperty("--clock", (k === 0 ? 1 - smooth(ramp(t, 0.8, 1)) : 0).toFixed(3));
      root.style.setProperty("--pos", (k === 1 ? smooth(ramp(t, 0, 0.15)) * (1 - smooth(ramp(t, 0.9, 1))) : 0).toFixed(3));
      root.style.setProperty("--timer", (k === 2 ? smooth(ramp(t, 0.5, 0.65)) : 0).toFixed(3));
      root.style.setProperty("--stock", (k === 3 ? smooth(ramp(t, 0.1, 0.25)) : 0).toFixed(3));
      root.style.setProperty("--sold", (k === 3 ? smooth(ramp(t, 0.9, 1)) : k > 3 ? 1 - finale : 0).toFixed(3));
      root.style.setProperty("--mine", (k === 4 ? smooth(ramp(t, 0.5, 0.8)) : 0).toFixed(3));

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
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  const c = CHAPTERS[chapter];
  return (
    <section ref={rootRef} className="fq-dshow" aria-label="한정판 드롭 구매 흐름">
      <div className="fq-dshow-stage">
        <canvas ref={canvasRef} aria-hidden="true" />
        <b ref={clockRef} className="fq-dshow-clock fq-num" aria-hidden="true">
          00:00:03
        </b>
        <div className="fq-dshow-pos" aria-hidden="true">
          <span>내 앞에 남은 팬</span>
          <b ref={posRef} className="fq-num">
            1,284
          </b>
        </div>
        <div className="fq-dshow-timer" aria-hidden="true">
          <span>구매 자격</span>
          <b ref={timerRef} className="fq-num">
            05:00
          </b>
        </div>
        <div className="fq-dshow-stock" aria-hidden="true">
          <span>남은 수량</span>
          <b ref={leftRef} className="fq-num">
            30
          </b>
        </div>
        <i className="fq-dshow-sold" aria-hidden="true">
          SOLD OUT
        </i>
        <div className="fq-dshow-mine" aria-hidden="true">
          <span>결제 기한 안에 결제하면</span>
          <b>구매 확정</b>
        </div>
        <div className="fq-dshow-copy">
          <span>LIMITED DROP</span>
          <h2 key={c.title}>{c.title}</h2>
          <p key={c.lead}>{c.lead}</p>
          <small>숫자와 줄 길이는 흐름을 보여 주는 예시예요.</small>
        </div>
        <ChapterHud names={CHAPTERS.map((x) => x.name)} index={chapter} skipTo={skipTo} />
      </div>
    </section>
  );
}
