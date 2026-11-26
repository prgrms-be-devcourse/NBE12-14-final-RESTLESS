"use client";

import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import ChapterHud from "@/components/chapter/ChapterHud";
import { chapterAt, mix, ramp, smooth } from "@/lib/chapters";
import "./Lightstick.css";

export type StickColor = "BLACK" | "WHITE" | "SILVER";

const BODY: Record<StickColor, { color: number; metalness: number; roughness: number; tint: string }> = {
  BLACK: { color: 0x1a1a22, metalness: 0.2, roughness: 0.32, tint: "#6a57f2" },
  WHITE: { color: 0xeeecf4, metalness: 0.05, roughness: 0.28, tint: "#7db4ff" },
  SILVER: { color: 0xc9cdd8, metalness: 1, roughness: 0.22, tint: "#a5f2d3" },
};
const COLOR_ORDER: StickColor[] = ["BLACK", "WHITE", "SILVER"];
const COLOR_NAME: Record<StickColor, string> = { BLACK: "블랙", WHITE: "화이트", SILVER: "실버 한정" };

/** 부품 하나. 조립 위치에서 분해 방향(dx, dy)만큼 벌어진다 */
type Part = { key: string; label: string; detail: string; obj: THREE.Object3D; base: THREE.Vector3; dx: number; dy: number; spin: number };
/** 분해 장에서 위에서부터 한 개씩 푸는 순서 */
const OPEN_ORDER = ["cap-top", "dome", "core", "collar", "pcb", "cells", "grip", "cap-end"];

/** 장 길이 비율: 점등, 한 바퀴, 분해, 색, 객석 */
const SPANS = [1.2, 1.4, 2.6, 1.3, 1.5];
const CHAPTERS = [
  { name: "점등", title: "AFTERGLOW", sub: "NOVA:LINE 2026 투어 공식 응원봉." },
  { name: "한 바퀴", title: "360°", sub: "" },
  { name: "분해", title: "부품 8개", sub: "부품 구성을 하나씩 보여 드려요." },
  { name: "색", title: "", sub: "블랙, 화이트, 실버 3종. 실버는 한정 수량이에요." },
  { name: "객석", title: "LIGHT ON", sub: "옵션을 골라 구매할 수 있어요." },
];
const SPIN_NOTES = ["손잡이에 투어 로고와 미끄럼 방지 링", "돔을 통해 360도로 고르게 퍼지는 빛", "가볍게 165g, 한 손으로 세 시간"];

/**
 * 응원봉 쇼케이스. 장 5개로 나뉜 긴 스크롤 연출이다.
 * 1) 어둠 속 Q 코어만 켜져 있다가 카메라가 물러나며 응원봉이 드러난다. 2) 한 바퀴 돌며 특징을 하나씩 보여 준다.
 * 3) 부품을 위에서부터 한 개씩 풀고, 카메라가 그 부품을 따라간다. 4) 스크롤에 따라 색이 바뀐다.
 * 5) 응원봉이 객석 수백 개로 늘어나며 공연장이 된다.
 * 모델은 three.js 기본 도형으로 직접 만든다. 외부 영상이나 3D 파일을 쓰지 않는다.
 */
export default function Lightstick({ color, onColor, colors, buyHref }: { color: StickColor; onColor?: (c: StickColor) => void; colors: StickColor[]; buyHref: string }) {
  const rootRef = useRef<HTMLElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const colorRef = useRef(color);
  colorRef.current = color;
  const [chapter, setChapter] = useState(0);
  const [note, setNote] = useState(0);
  const [part, setPart] = useState(-1);
  const [shownColor, setShownColor] = useState<StickColor>(color);
  const [failed, setFailed] = useState(false);
  const [labels, setLabels] = useState<{ key: string; label: string; detail: string }[]>([]);

  useEffect(() => {
    const root = rootRef.current!;
    const canvas = canvasRef.current!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
    } catch {
      // WebGL을 못 쓰는 브라우저에서는 쇼케이스를 숨긴다. 구매 화면은 영향을 받지 않는다
      setFailed(true);
      return;
    }
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;

    const scene = new THREE.Scene();
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envTex = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
    scene.environment = envTex;
    const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 200);

    // 빛. 점등 장에서는 꺼져 있다가 켜진다
    const hemi = new THREE.HemisphereLight(0xbfb4ff, 0x120c24, 0.6);
    scene.add(hemi);
    const key = new THREE.DirectionalLight(0xffffff, 1.6);
    key.position.set(3, 4, 5);
    scene.add(key);
    const rimA = new THREE.PointLight(0xff7a93, 14, 12);
    rimA.position.set(-3, 1.5, -2);
    scene.add(rimA);
    const rimB = new THREE.PointLight(0x5ad1ff, 12, 12);
    rimB.position.set(3, -1.5, -2.5);
    scene.add(rimB);

    // 재질
    const b0 = BODY[colorRef.current];
    const body = new THREE.MeshPhysicalMaterial({ color: b0.color, metalness: b0.metalness, roughness: b0.roughness, clearcoat: 1, clearcoatRoughness: 0.12 });
    const chrome = new THREE.MeshPhysicalMaterial({ color: 0xe8e6f0, metalness: 1, roughness: 0.12 });
    const accent = new THREE.MeshPhysicalMaterial({ color: 0xb7a4ff, metalness: 0.9, roughness: 0.2, emissive: 0x6a57f2, emissiveIntensity: 0.25 });
    const glass = new THREE.MeshPhysicalMaterial({
      color: 0xffffff, metalness: 0, roughness: 0.04, transmission: 1, thickness: 0.35, ior: 1.42,
      clearcoat: 1, clearcoatRoughness: 0.05, attenuationColor: new THREE.Color(0xd9d0ff), attenuationDistance: 2.5,
    });
    const core = new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xb7a4ff, emissiveIntensity: 2.2, roughness: 0.4 });
    const pcb = new THREE.MeshStandardMaterial({ color: 0x0f3d2e, roughness: 0.6, metalness: 0.2 });
    const led = new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xffd36e, emissiveIntensity: 2 });
    const cell = new THREE.MeshPhysicalMaterial({ color: 0x2f8fe0, metalness: 0.6, roughness: 0.3, clearcoat: 1 });
    const rubber = new THREE.MeshStandardMaterial({ color: 0x24222c, roughness: 0.85 });

    const stick = new THREE.Group();
    scene.add(stick);
    const list: Part[] = [];
    const add = (k: string, label: string, detail: string, obj: THREE.Object3D, y: number, dx: number, dy: number, spin = 0) => {
      obj.position.set(0, y, 0);
      stick.add(obj);
      list.push({ key: k, label, detail, obj, base: obj.position.clone(), dx, dy, spin });
    };

    {
      const g = new THREE.Group();
      g.add(new THREE.Mesh(new THREE.CylinderGeometry(0.27, 0.24, 0.16, 48), chrome));
      const loop = new THREE.Mesh(new THREE.TorusGeometry(0.11, 0.025, 16, 40), chrome);
      loop.position.y = -0.17;
      g.add(loop);
      add("cap-end", "엔드캡", "스트랩 고리 일체형", g, -1.62, 0, -1.05, 0.6);
    }
    {
      const pts: THREE.Vector2[] = [];
      for (let i = 0; i <= 24; i++) {
        const t = i / 24;
        const r = 0.255 - Math.sin(t * Math.PI) * 0.03 + (t > 0.92 ? (t - 0.92) * 0.5 : 0);
        pts.push(new THREE.Vector2(r, -0.72 + t * 1.44));
      }
      const g = new THREE.Group();
      g.add(new THREE.Mesh(new THREE.LatheGeometry(pts, 64), body));
      for (let i = 0; i < 5; i++) {
        const ring = new THREE.Mesh(new THREE.TorusGeometry(0.235, 0.012, 12, 64), rubber);
        ring.rotation.x = Math.PI / 2;
        ring.position.y = -0.5 + i * 0.12;
        g.add(ring);
      }
      const btn = new THREE.Mesh(new THREE.CapsuleGeometry(0.035, 0.08, 6, 16), accent);
      btn.position.set(0, 0.32, 0.24);
      g.add(btn);
      add("grip", "그립 핸들", "미끄럼 방지 링 5줄", g, -0.82, 0, -0.5, -0.4);
    }
    {
      const g = new THREE.Group();
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * Math.PI * 2;
        const c = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 0.62, 32), cell);
        c.position.set(Math.cos(a) * 0.09, 0, Math.sin(a) * 0.09);
        const tip = new THREE.Mesh(new THREE.CylinderGeometry(0.025, 0.025, 0.04, 16), chrome);
        tip.position.set(c.position.x, 0.33, c.position.z);
        g.add(c, tip);
      }
      add("cells", "배터리", "AAA 3개, 연속 18시간", g, -0.82, 1.5, -0.1, 1.2);
    }
    {
      const g = new THREE.Group();
      g.add(new THREE.Mesh(new THREE.CylinderGeometry(0.2, 0.2, 0.035, 40), pcb));
      for (let i = 0; i < 6; i++) {
        const a = (i / 6) * Math.PI * 2;
        const l = new THREE.Mesh(new THREE.SphereGeometry(0.028, 12, 12), led);
        l.position.set(Math.cos(a) * 0.12, 0.03, Math.sin(a) * 0.12);
        g.add(l);
      }
      const chip = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.025, 0.07), rubber);
      chip.position.y = 0.03;
      g.add(chip);
      add("pcb", "회로 기판", "RGB LED 6개, 블루투스 연동", g, -0.04, -1.05, 0.15, 0.9);
    }
    {
      const ring = new THREE.Mesh(new THREE.TorusGeometry(0.27, 0.05, 24, 80), accent);
      ring.rotation.x = Math.PI / 2;
      const g = new THREE.Group();
      g.add(ring);
      add("collar", "컬러 링", "곡마다 바뀌는 무대 색", g, 0.04, 0, 0.42);
    }
    {
      const g = new THREE.Group();
      g.add(new THREE.Mesh(new THREE.TorusGeometry(0.26, 0.055, 24, 80), core));
      const tail = new THREE.Mesh(new THREE.CapsuleGeometry(0.045, 0.16, 8, 16), core);
      tail.position.set(0.2, -0.22, 0);
      tail.rotation.z = Math.PI / 4;
      g.add(tail);
      add("core", "Q 코어", "FANQ 로고 모양 발광체", g, 0.58, 0, 0.82, 0.3);
    }
    add("dome", "글라스 돔", "빛을 고르게 퍼뜨리는 반투명 구", new THREE.Mesh(new THREE.SphereGeometry(0.6, 64, 48), glass), 0.58, 0, 1.45);
    add("cap-top", "톱 캡", "돔을 고정하는 금속 덮개", new THREE.Mesh(new THREE.SphereGeometry(0.13, 32, 16, 0, Math.PI * 2, 0, Math.PI / 2), chrome), 1.15, 0, 2.05);
    const ordered = OPEN_ORDER.map((k) => list.find((p) => p.key === k)!);
    setLabels(ordered.map(({ key: k, label, detail }) => ({ key: k, label, detail })));
    const corePart = list.find((x) => x.key === "core")!;

    // 코어 빛 번짐
    const glowTex = (() => {
      const c = document.createElement("canvas");
      c.width = c.height = 128;
      const g = c.getContext("2d")!;
      const grd = g.createRadialGradient(64, 64, 0, 64, 64, 64);
      grd.addColorStop(0, "rgba(255,255,255,1)");
      grd.addColorStop(0.25, "rgba(200,180,255,0.55)");
      grd.addColorStop(1, "rgba(120,90,255,0)");
      g.fillStyle = grd;
      g.fillRect(0, 0, 128, 128);
      return new THREE.CanvasTexture(c);
    })();
    const glowMat = new THREE.SpriteMaterial({ map: glowTex, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true });
    const glow = new THREE.Sprite(glowMat);
    stick.add(glow);
    const coreLight = new THREE.PointLight(0xb7a4ff, 6, 4);
    stick.add(coreLight);

    // 객석: 작은 응원봉 수백 개. 머리만 빛나는 공과 손잡이 막대를 인스턴스로 한 번에 그린다
    const ROWS = 14;
    const PER = 44;
    const CROWD = ROWS * PER;
    const headMat = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0 });
    const heads = new THREE.InstancedMesh(new THREE.SphereGeometry(0.16, 10, 8), headMat, CROWD);
    const handleMat = new THREE.MeshBasicMaterial({ color: 0x22202a, transparent: true, opacity: 0 });
    const handles = new THREE.InstancedMesh(new THREE.CylinderGeometry(0.04, 0.05, 0.6, 6), handleMat, CROWD);
    const seats: { x: number; y: number; z: number; ph: number; row: number }[] = [];
    const hue = new THREE.Color();
    for (let r = 0; r < ROWS; r++) {
      for (let i = 0; i < PER; i++) {
        const idx = r * PER + i;
        const x = (i - PER / 2) * 0.9 + (Math.random() - 0.5) * 0.4;
        const z = -3 - r * 1.6;
        const y = -2.6 + r * 0.32;
        seats.push({ x, y, z, ph: Math.random() * Math.PI * 2, row: r });
        hue.setHSL(0.68 + Math.random() * 0.22, 0.85, 0.7);
        heads.setColorAt(idx, hue);
      }
    }
    scene.add(heads, handles);
    const m4 = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const s1 = new THREE.Vector3(1, 1, 1);
    const pos = new THREE.Vector3();
    const zAxis = new THREE.Vector3(0, 0, 1);

    let w = 0;
    let h = 0;
    const resize = () => {
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      renderer.setSize(w, h, false);
      camera.aspect = w / Math.max(1, h);
      camera.updateProjectionMatrix();
    };
    resize();

    let target = 0;
    let shown = 0;
    let visible = false;
    let raf = 0;
    let last = 0;
    const st = { chapter: -1, note: -1, part: -2, color: "" };
    const readScroll = () => {
      const rect = root.getBoundingClientRect();
      const span = rect.height - window.innerHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -rect.top / span)) : 0;
    };
    const coreHue = new THREE.Color();
    const cA = new THREE.Color();
    const cB = new THREE.Color();
    const look = new THREE.Vector3();
    const lookNow = new THREE.Vector3(0, 0, 0);
    const camNow = new THREE.Vector3(0, 0.6, 2.6);

    const frame = (now: number) => {
      raf = 0;
      const dt = Math.min(50, now - (last || now)) / 16.7;
      last = now;
      shown += (target - shown) * Math.min(1, 0.1 * dt);
      const ch = chapterAt(shown, SPANS);
      const t = ch.local;

      // ── 장마다 값 정하기 ──
      let lights = 1;
      let lit = 1; // 코어 밝기
      let spinY = 0;
      let crowd = 0;
      const opens = new Array(OPEN_ORDER.length).fill(0);
      let focusPart = -1;
      const camTo = new THREE.Vector3(0, 0.15, 7.4);
      look.set(0, -0.1, 0);
      let colorNow: StickColor = colorRef.current;
      let colorMix = 0;
      let colorNext: StickColor = colorNow;
      let noteIdx = 0;

      if (ch.index === 0) {
        lights = smooth(ramp(t, 0.35, 0.85));
        lit = 1.4;
        const pull = smooth(ramp(t, 0.15, 1));
        camTo.set(0, mix(0.6, 0.15, pull), mix(2.6, 7.4, pull));
        look.set(0, mix(0.55, -0.1, pull), 0);
      } else if (ch.index === 1) {
        spinY = smooth(t) * Math.PI * 2;
        noteIdx = Math.min(2, Math.floor(t * 3));
        camTo.set(Math.sin(t * Math.PI) * 1.2, 0.3, 6.6);
      } else if (ch.index === 2) {
        // 0~86%: 한 부품씩 풀린다. 86% 이후: 다시 조립
        const close = smooth(ramp(t, 0.86, 1));
        const step = 0.86 / OPEN_ORDER.length;
        for (let k = 0; k < OPEN_ORDER.length; k++) opens[k] = smooth(ramp(t, k * step, k * step + step * 0.6)) * (1 - close);
        focusPart = close > 0.3 ? -1 : Math.min(OPEN_ORDER.length - 1, Math.floor(t / step));
        lit = 0.45 + close * 0.5;
        spinY = t * Math.PI * 0.8;
        camTo.set(0, 0.2, mix(9.5, 7.4, close));
      } else if (ch.index === 3) {
        const pos3 = t * (COLOR_ORDER.length - 1);
        const ci = Math.min(COLOR_ORDER.length - 2, Math.floor(pos3));
        const f = smooth(ramp(pos3 - ci, 0.35, 0.65));
        colorNow = COLOR_ORDER[ci];
        colorNext = COLOR_ORDER[ci + 1];
        colorMix = f;
        spinY = Math.PI * 0.25 + t * Math.PI * 0.5;
        camTo.set(0, 0.2, 6.8);
      } else {
        crowd = smooth(ramp(t, 0.05, 0.75));
        lit = 1.2 + smooth(ramp(t, 0.6, 1)) * 0.8;
        camTo.set(0, mix(0.15, 2.4, crowd), mix(7.4, 15, crowd));
        look.set(0, mix(-0.1, -1.2, crowd), mix(0, -6, crowd));
      }
      // 세로 화면은 조금 멀리서 본다
      if (h > w) camTo.z *= 1.45;

      // 부품 위치
      ordered.forEach((p, k) => {
        const o = opens[k];
        p.obj.position.set(p.base.x + p.dx * o, p.base.y + p.dy * o, p.base.z);
        p.obj.rotation.y = p.spin * o * Math.PI;
      });
      if (focusPart >= 0) {
        const p = ordered[focusPart];
        look.set(p.obj.position.x * 0.6, p.obj.position.y - 0.1, 0);
        camTo.y = look.y + 0.2;
      }
      const idle = reduce ? 0 : Math.sin(now / 2400) * 0.18;
      stick.rotation.y = spinY + idle;
      stick.rotation.z = 0;

      // 카메라는 목표로 부드럽게 따라간다
      camNow.lerp(camTo, Math.min(1, 0.12 * dt));
      lookNow.lerp(look, Math.min(1, 0.12 * dt));
      camera.position.copy(camNow);
      camera.lookAt(lookNow);

      // 빛과 재질
      // 주변 반사광도 함께 꺼야 어둠 속에서 코어만 보인다
      scene.environmentIntensity = 0.05 + lights * 0.95;
      hemi.intensity = 0.6 * lights;
      key.intensity = 1.6 * lights;
      rimA.intensity = 14 * lights;
      rimB.intensity = 12 * lights;
      renderer.toneMappingExposure = 0.6 + lights * 0.45;
      const bA = BODY[colorNow];
      const bB = BODY[colorNext];
      cA.setHex(bA.color);
      cB.setHex(bB.color);
      body.color.copy(cA.lerp(cB, colorMix));
      body.metalness = mix(bA.metalness, bB.metalness, colorMix);
      body.roughness = mix(bA.roughness, bB.roughness, colorMix);
      const shownC = colorMix > 0.5 ? colorNext : colorNow;
      root.style.setProperty("--tint", BODY[shownC].tint);
      root.style.setProperty("--p", shown.toFixed(4));

      glow.position.copy(corePart.obj.position);
      coreLight.position.copy(corePart.obj.position);
      const pulse = reduce ? 1 : 0.92 + Math.sin(now / 420) * 0.08;
      glow.scale.setScalar((1.4 + lit * 1.5) * pulse);
      glowMat.opacity = Math.min(1, 0.2 + lit * 0.5);
      coreHue.setHSL((0.72 + shown * 0.4 + (reduce ? 0 : now / 30000)) % 1, 0.8, 0.68);
      core.emissive.copy(coreHue);
      core.emissiveIntensity = 0.6 + lit * 2;
      coreLight.color.copy(coreHue);
      coreLight.intensity = 2 + lit * 7;
      accent.emissive.copy(coreHue);

      // 객석: 앞줄부터 켜지고 같은 박자로 흔들린다
      headMat.opacity = crowd;
      handleMat.opacity = crowd * 0.9;
      if (crowd > 0) {
        for (let i = 0; i < CROWD; i++) {
          const s = seats[i];
          const on = ramp(crowd, s.row / ROWS * 0.8, s.row / ROWS * 0.8 + 0.2);
          const wave = reduce ? 0 : Math.sin(now / 260 - s.x * 0.35 + s.ph * 0.2);
          const tilt = wave * 0.35;
          q.setFromAxisAngle(zAxis, tilt);
          const sc = on;
          pos.set(s.x, s.y + (1 - on) * -1.5, s.z);
          m4.compose(pos, q, s1.set(sc, sc, sc));
          handles.setMatrixAt(i, m4);
          pos.set(s.x - Math.sin(tilt) * 0.38, s.y + Math.cos(tilt) * 0.38 + (1 - on) * -1.5, s.z);
          m4.compose(pos, q, s1.set(sc, sc, sc));
          heads.setMatrixAt(i, m4);
        }
        heads.instanceMatrix.needsUpdate = true;
        handles.instanceMatrix.needsUpdate = true;
      }
      heads.visible = handles.visible = crowd > 0;

      renderer.render(scene, camera);

      if (ch.index !== st.chapter) {
        st.chapter = ch.index;
        setChapter(ch.index);
      }
      if (noteIdx !== st.note) {
        st.note = noteIdx;
        setNote(noteIdx);
      }
      if (focusPart !== st.part) {
        st.part = focusPart;
        setPart(focusPart);
      }
      if (shownC !== st.color) {
        st.color = shownC;
        setShownColor(shownC);
      }
      if (visible) raf = requestAnimationFrame(frame);
    };

    const kick = () => {
      if (!raf && visible) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      readScroll();
      kick();
    };
    const io = new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      if (visible) {
        last = 0;
        kick();
      }
    });
    io.observe(root);
    readScroll();
    shown = target;
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", resize);

    return () => {
      cancelAnimationFrame(raf);
      io.disconnect();
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", resize);
      scene.traverse((o) => {
        const mesh = o as THREE.Mesh;
        mesh.geometry?.dispose();
      });
      [body, chrome, accent, glass, core, pcb, led, cell, rubber, glowMat, headMat, handleMat].forEach((m) => m.dispose());
      glowTex.dispose();
      envTex.dispose();
      pmrem.dispose();
      renderer.dispose();
    };
  }, []);

  if (failed) return null;
  const cur = CHAPTERS[chapter];
  const title = chapter === 1 ? "360°" : chapter === 3 ? COLOR_NAME[shownColor] : cur.title;
  const sub = chapter === 1 ? SPIN_NOTES[note] : cur.sub;
  const cp = part >= 0 ? labels[part] : null;

  return (
    <section ref={rootRef} className="fq-stick" aria-label="응원봉 구조 살펴보기">
      <div className="fq-stick-stage">
        <div className="fq-stick-tint" aria-hidden="true" />
        <canvas ref={canvasRef} className="fq-stick-canvas" aria-hidden="true" />

        <div className="fq-stick-copy">
          <span className="fq-stick-no fq-num">{String(chapter + 1).padStart(2, "0")} / 05</span>
          <h2 key={title} className="fq-stick-title">
            {title}
          </h2>
          <p key={sub} className="fq-stick-sub">
            {sub}
          </p>
          {onColor ? (
            <div className="fq-stick-colors" role="radiogroup" aria-label="응원봉 색">
              {colors.map((c) => (
                <button
                  key={c}
                  type="button"
                  role="radio"
                  aria-checked={c === color}
                  aria-label={COLOR_NAME[c]}
                  className={`is-${c.toLowerCase()}${c === (chapter === 3 ? shownColor : color) ? " is-on" : ""}`}
                  onClick={() => onColor(c)}
                />
              ))}
            </div>
          ) : null}
        </div>

        {/* 분해 장: 지금 풀린 부품 설명 */}
        {cp ? (
          <div key={cp.key} className="fq-stick-part">
            <span className="fq-num">
              {String(part + 1).padStart(2, "0")} / {String(labels.length).padStart(2, "0")}
            </span>
            <b>{cp.label}</b>
            <small>{cp.detail}</small>
          </div>
        ) : null}

        <a className={`fq-stick-buy${chapter === 4 ? " is-on" : ""}`} href={buyHref} tabIndex={chapter === 4 ? 0 : -1}>
          옵션 고르러 가기 ↑
        </a>

        <ChapterHud names={CHAPTERS.map((c) => c.name)} index={chapter} skipTo={buyHref} skipLabel="옵션과 배송지로 이동 ↑" />
      </div>
    </section>
  );
}
