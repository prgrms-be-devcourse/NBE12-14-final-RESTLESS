// Lucian UX/UI Prompt #330, MIT License (c) 2026 Seokwoooo, https://lucianlabs.dev/uxui-prompt/#330
"use client";

import { useEffect, useRef } from "react";

type Props = {
  /** true면 워프 속도로 가속 */
  warp: boolean;
  /** 별 개수 배율(화면 넓이에 맞춰 조금씩 늘린다) */
  density?: number;
  /** dark: 우주(기본), light: 새벽하늘 */
  tone?: "dark" | "light";
  /** 페이지를 스크롤하면 그 속도만큼 잠깐 빨라진다 */
  scrollBoost?: boolean;
  /** false면 마우스를 따라 소실점이 움직이지 않는다(배경용) */
  interactive?: boolean;
  /** 순항 속도 배율. 배경으로 깔 때는 1보다 작게 */
  speed?: number;
  /** 현재 배속(×1.0 ~)을 매 프레임 알려준다 */
  onSpeed?: (mult: number) => void;
  className?: string;
};

type Star = { x: number; y: number; z: number; pz: number; c: number };

const BG = { dark: "2,3,10", light: "236,233,247" };

/**
 * 원근 투영 별빛 공간. 화면 좌표 = 소실점 + (x, y) ÷ 깊이.
 * 지난 깊이에서 지금 깊이까지 선을 그어 속도만큼 늘어난 빛줄기를 만든다.
 * 원본(#330)의 고정 340×330 캔버스를 부모 크기에 맞춰 늘리고, 밝은 테마와 스크롤 가속을 더했다.
 */
export default function Starfield({
  warp,
  density = 1,
  tone = "dark",
  scrollBoost = false,
  interactive = true,
  speed = 1,
  onSpeed,
  className,
}: Props) {
  const cvRef = useRef<HTMLCanvasElement>(null);
  const warpRef = useRef(warp);
  const toneRef = useRef(tone);
  const speedCb = useRef(onSpeed);
  warpRef.current = warp;
  toneRef.current = tone;
  speedCb.current = onSpeed;

  useEffect(() => {
    const cv = cvRef.current!;
    const cx = cv.getContext("2d")!;
    const rm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let W = 0;
    let H = 0;
    let F = 150;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const stars: Star[] = [];
    const spawn = (s: Star, far: boolean) => {
      s.x = (Math.random() - 0.5) * 2;
      s.y = (Math.random() - 0.5) * 2;
      s.z = far ? 1 : 0.05 + Math.random() * 0.95;
      s.pz = s.z;
      s.c = Math.random();
      return s;
    };
    const bg = (a: number) => `rgba(${BG[toneRef.current]},${a})`;

    const resize = () => {
      const r = cv.getBoundingClientRect();
      W = Math.max(1, r.width);
      H = Math.max(1, r.height);
      cv.width = W * dpr;
      cv.height = H * dpr;
      cx.setTransform(dpr, 0, 0, dpr, 0, 0);
      F = Math.max(W, H) * 0.45;
      const want = Math.max(120, Math.round(Math.min(900, ((W * H) / (340 * 330)) * 280 * 0.55 * density)));
      while (stars.length < want) stars.push(spawn({ x: 0, y: 0, z: 0, pz: 0, c: 0 }, false));
      stars.length = want;
      cx.fillStyle = bg(1);
      cx.fillRect(0, 0, W, H);
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(cv);

    const BASE = 0.22 * speed;
    let v = BASE;
    let kick = 0;
    const aim = { x: 0, y: 0 };
    const steer = { x: 0, y: 0 };

    const draw = (dt: number) => {
      const light = toneRef.current === "light";
      cx.fillStyle = bg(warpRef.current || v > 0.5 ? 0.35 : 0.9);
      cx.fillRect(0, 0, W, H);
      const ox = W / 2 + steer.x * W * 0.2;
      const oy = H / 2 + steer.y * H * 0.18;
      const blue = Math.min(1, (v - BASE) / 1.6);
      cx.lineCap = "round";
      for (const s of stars) {
        s.pz = s.z;
        s.z -= v * dt;
        if (s.z <= 0.02) {
          spawn(s, true);
          continue;
        }
        const x = ox + (s.x / s.z) * F;
        const y = oy + (s.y / s.z) * F;
        const px = ox + (s.x / (s.pz + v * 0.03)) * F;
        const py = oy + (s.y / (s.pz + v * 0.03)) * F;
        if (x < -20 || x > W + 20 || y < -20 || y > H + 20) {
          spawn(s, true);
          continue;
        }
        const a = Math.min(1, (1 - s.z) * 1.4);
        const w = Math.max(0.6, (1 - s.z) * 2.6);
        // 워프할수록 푸르게, 일부 별은 축제 느낌의 분홍, 보라로
        const pinkish = s.c > 0.82;
        let r: number, g: number, b: number;
        if (light) {
          r = pinkish ? 214 : Math.round(106 - blue * 40 * s.c);
          g = pinkish ? 80 : Math.round(87 + blue * 50);
          b = pinkish ? 150 : 242;
        } else {
          r = pinkish ? 255 : Math.round(255 - blue * 120 * s.c);
          g = pinkish ? Math.round(170 + 60 * (1 - blue)) : Math.round(255 - blue * 40);
          b = 255;
        }
        cx.strokeStyle = `rgba(${r},${g},${b},${(light ? a * 0.75 : a).toFixed(2)})`;
        cx.lineWidth = w;
        cx.beginPath();
        cx.moveTo(px, py);
        cx.lineTo(x + 0.01, y);
        cx.stroke();
      }
      if (blue > 0.05) {
        const gl = cx.createRadialGradient(ox, oy, 0, ox, oy, Math.max(W, H) * 0.35);
        const c = light ? "123,97,255" : "120,190,255";
        gl.addColorStop(0, `rgba(${c},${(0.25 * blue).toFixed(2)})`);
        gl.addColorStop(1, `rgba(${c},0)`);
        cx.fillStyle = gl;
        cx.fillRect(0, 0, W, H);
      }
    };

    let raf = 0;
    let last = 0;
    let vis = true;
    const frame = (now: number) => {
      raf = 0;
      if (!vis) return;
      const dt = Math.min(0.05, Math.max(0, (now - last) / 1000));
      last = now;
      kick *= Math.pow(0.08, dt);
      const target = (warpRef.current ? 3.2 : BASE) + kick;
      v += (target - v) * (1 - Math.pow(warpRef.current ? 0.25 : 0.12, dt));
      steer.x += (aim.x - steer.x) * (1 - Math.pow(0.05, dt));
      steer.y += (aim.y - steer.y) * (1 - Math.pow(0.05, dt));
      draw(dt);
      speedCb.current?.(v / BASE);
      raf = requestAnimationFrame(frame);
    };
    const run = () => {
      if (rm) {
        // 모션 줄이기: 정지된 별빛 한 장만 그린다
        cx.fillStyle = bg(1);
        cx.fillRect(0, 0, W, H);
        draw(0.016);
        return;
      }
      if (!raf && vis) {
        last = performance.now();
        raf = requestAnimationFrame(frame);
      }
    };
    const io = new IntersectionObserver((es) => {
      vis = es[0].isIntersecting;
      run();
    });
    io.observe(cv);

    const onMove = (e: PointerEvent) => {
      aim.x = (e.clientX / window.innerWidth - 0.5) * 2;
      aim.y = (e.clientY / window.innerHeight - 0.5) * 2;
    };
    const onLeave = () => {
      aim.x = 0;
      aim.y = 0;
    };
    let lastY = window.scrollY;
    const onScroll = () => {
      const dy = window.scrollY - lastY;
      lastY = window.scrollY;
      kick = Math.min(1.6, kick + Math.abs(dy) * 0.004);
    };
    if (interactive) {
      window.addEventListener("pointermove", onMove);
      document.addEventListener("pointerleave", onLeave);
    }
    if (scrollBoost) window.addEventListener("scroll", onScroll, { passive: true });
    run();

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      io.disconnect();
      window.removeEventListener("pointermove", onMove);
      document.removeEventListener("pointerleave", onLeave);
      window.removeEventListener("scroll", onScroll);
    };
  }, [density, scrollBoost, interactive, speed]);

  return <canvas ref={cvRef} className={className} role="img" aria-label="별빛 공간" style={{ display: "block", width: "100%", height: "100%" }} />;
}
