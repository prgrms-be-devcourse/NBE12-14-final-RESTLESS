"use client";

import { useEffect, useRef, type MutableRefObject } from "react";

/** 장면을 스크롤로 조절하는 값. 모두 0~1. 기본은 다 켜진 상태다 */
export type StageControl = { beams: number; crowd: number; screen: number };

/**
 * 공연장 그림. 위에서 내려오는 조명 빛줄기와, 객석을 채운 응원봉 불빛이 파도처럼 일렁인다.
 * 사진 대신 캔버스로 그려서 어떤 공연에도 쓸 수 있다. 화면 밖이면 멈춘다.
 * intensity(0~1)를 올리면 조명이 밝아지고 응원봉이 더 크게 흔들린다.
 */
export default function StageScene({
  seed = 1,
  intensity = 0,
  className,
  control,
  screenText,
}: {
  seed?: number;
  intensity?: number;
  className?: string;
  /** 조명 개수, 객석 불빛, LED 화면을 따로 켜고 끈다. 렌더 없이 매 프레임 읽는다 */
  control?: MutableRefObject<StageControl>;
  /** LED 화면에 띄울 글 */
  screenText?: string;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  const power = useRef(intensity);
  power.current = intensity;
  const textRef = useRef(screenText);
  textRef.current = screenText;

  useEffect(() => {
    const canvas = ref.current!;
    const ctx = canvas.getContext("2d")!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    let w = 0;
    let h = 0;
    let raf = 0;
    let visible = true;

    // 공연마다 다른 색 조합
    const PALETTES = [
      ["183,164,255", "255,154,174", "125,180,255"],
      ["255,122,147", "255,211,110", "183,164,255"],
      ["90,209,255", "165,242,211", "183,164,255"],
    ];
    const pal = PALETTES[Math.abs(seed) % PALETTES.length];

    type Fan = { x: number; y: number; z: number; ph: number; c: string };
    let crowd: Fan[] = [];
    const build = () => {
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // 객석: 화면 아래 45%에 원근을 주어 뒤로 갈수록 작고 촘촘하게 둔다
      const rows = 26;
      crowd = [];
      for (let r = 0; r < rows; r++) {
        const z = r / (rows - 1);
        const y = h * (0.58 + Math.pow(z, 1.6) * 0.44);
        const gap = 7 + z * 26;
        for (let x = -gap; x < w + gap; x += gap) {
          crowd.push({ x: x + (Math.random() - 0.5) * gap * 0.8, y: y + (Math.random() - 0.5) * 6, z, ph: Math.random() * Math.PI * 2, c: pal[(Math.random() * pal.length) | 0] });
        }
      }
    };
    build();

    const beams = Array.from({ length: 7 }, (_, i) => ({ x: (i + 0.5) / 7, sp: 0.3 + Math.random() * 0.5, ph: Math.random() * 6, c: pal[i % pal.length] }));

    const frame = (now: number) => {
      raf = 0;
      const t = reduce ? 0 : now / 1000;
      const k = power.current;
      const c = control?.current ?? { beams: 1, crowd: 1, screen: 1 };
      ctx.globalCompositeOperation = "source-over";
      ctx.fillStyle = "#04030a";
      ctx.fillRect(0, 0, w, h);

      // 무대 뒤 빛
      const glow = ctx.createRadialGradient(w / 2, h * 0.42, 0, w / 2, h * 0.42, w * 0.6);
      glow.addColorStop(0, `rgba(${pal[0]},${(0.28 + k * 0.25) * Math.max(c.beams, c.screen)})`);
      glow.addColorStop(1, "rgba(0,0,0,0)");
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, w, h);

      ctx.globalCompositeOperation = "lighter";
      // 조명 빛줄기: 무대 위 한 점에서 객석 쪽으로 흔들리며 내려온다
      beams.forEach((b, bi) => {
        // 조명이 왼쪽부터 하나씩 켜진다
        const on = Math.min(1, Math.max(0, c.beams * beams.length - bi));
        if (on <= 0) return;
        const sx = w * b.x;
        const ang = Math.sin(t * b.sp + b.ph) * 0.5;
        const len = h * 1.1;
        const ex = sx + Math.sin(ang) * len;
        const ey = Math.cos(ang) * len;
        const spread = w * (0.05 + k * 0.03);
        const g = ctx.createLinearGradient(sx, 0, ex, ey);
        g.addColorStop(0, `rgba(${b.c},${(0.32 + k * 0.3) * on})`);
        g.addColorStop(1, `rgba(${b.c},0)`);
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.moveTo(sx - 3, 0);
        ctx.lineTo(sx + 3, 0);
        ctx.lineTo(ex + spread, ey);
        ctx.lineTo(ex - spread, ey);
        ctx.closePath();
        ctx.fill();
      });

      // 무대 뒤 LED 화면: 색이 천천히 흐른다
      const scr = c.screen;
      const sx0 = w * 0.3;
      const sw = w * 0.4;
      const sy0 = h * 0.2;
      const sh = h * 0.3;
      const screen = ctx.createLinearGradient(sx0, 0, sx0 + sw, 0);
      const shift = (Math.sin(t * 0.4) + 1) / 2;
      screen.addColorStop(0, `rgba(${pal[0]},${(0.18 + k * 0.2) * scr})`);
      screen.addColorStop(shift * 0.8 + 0.1, `rgba(${pal[1]},${(0.32 + k * 0.25) * scr})`);
      screen.addColorStop(1, `rgba(${pal[2]},${(0.18 + k * 0.2) * scr})`);
      ctx.fillStyle = screen;
      ctx.fillRect(sx0, sy0, sw, sh);
      ctx.fillStyle = "rgba(4,3,10,0.35)";
      for (let gx = sx0; gx < sx0 + sw; gx += 6) ctx.fillRect(gx, sy0, 1, sh);
      if (textRef.current && scr > 0.05) {
        ctx.globalCompositeOperation = "lighter";
        ctx.fillStyle = `rgba(255,255,255,${0.85 * scr})`;
        ctx.font = `900 ${Math.round(sh * 0.32)}px "Bodoni 72", Didot, Georgia, sans-serif`;
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText(textRef.current, sx0 + sw / 2, sy0 + sh / 2, sw * 0.9);
        ctx.globalCompositeOperation = "source-over";
      }

      // 무대 바닥선
      const floor = ctx.createLinearGradient(0, h * 0.55, 0, h * 0.6);
      floor.addColorStop(0, `rgba(${pal[1]},${0.25 + k * 0.2})`);
      floor.addColorStop(1, "rgba(0,0,0,0)");
      ctx.fillStyle = floor;
      ctx.fillRect(w * 0.15, h * 0.55, w * 0.7, h * 0.05);

      // 응원봉 불빛: 같은 박자로 왼쪽에서 오른쪽으로 파도가 지나간다
      for (const f of crowd) {
        // 앞줄(아래쪽)부터 불빛이 켜진다
        const lit = Math.min(1, Math.max(0, (c.crowd - (1 - f.z)) * 6));
        if (lit <= 0) continue;
        const wave = Math.sin(t * 2.4 - f.x / 90 + f.ph * 0.3);
        const lift = (wave * 0.5 + 0.5) * (3 + f.z * 9) * (0.6 + k);
        const r = 0.6 + f.z * 1.9;
        const a = (0.35 + f.z * 0.5) * (0.7 + wave * 0.3) * lit;
        ctx.fillStyle = `rgba(${f.c},${a * 0.55})`;
        ctx.beginPath();
        ctx.arc(f.x, f.y - lift, r * 1.7, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = `rgba(255,255,255,${a * 0.9})`;
        ctx.beginPath();
        ctx.arc(f.x, f.y - lift, r * 0.7, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalCompositeOperation = "source-over";

      // 아래쪽을 어둡게 눌러 글이 잘 보이게 한다
      const shade = ctx.createLinearGradient(0, h * 0.6, 0, h);
      shade.addColorStop(0, "rgba(4,3,10,0)");
      shade.addColorStop(1, "rgba(4,3,10,0.55)");
      ctx.fillStyle = shade;
      ctx.fillRect(0, h * 0.6, w, h * 0.4);

      if (visible && !reduce) raf = requestAnimationFrame(frame);
    };

    const io = new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      if (visible && !raf) raf = requestAnimationFrame(frame);
    });
    io.observe(canvas);
    const onResize = () => {
      build();
      if (!raf) raf = requestAnimationFrame(frame);
    };
    window.addEventListener("resize", onResize);
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("resize", onResize);
    };
  }, [seed]);

  return <canvas ref={ref} className={className} aria-hidden="true" />;
}
