"use client";

import { useEffect, useRef, useState, type MutableRefObject } from "react";
import * as THREE from "three";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import { mix, ramp, smooth } from "@/lib/chapters";

/** 궤도가 매 프레임 알려 주는 마무리 연출 위치. index는 마무리 장 번호(0~4), 아직 아니면 -1 */
export type FinaleState = { index: number; local: number };

// 상자 크기(가로, 세로 깊이, 높이, 벽 두께)와 칸 가운데 좌표
const W = 3.6;
const D = 3.2;
const H = 0.75;
const T = 0.06;
const CELLS: [number, number][] = [
  [-0.87, -0.77],
  [0.87, -0.77],
  [-0.87, 0.77],
  [0.87, 0.77],
];
const LID_OPEN = -1.95;
const FONT = '"Pretendard Variable", Pretendard, -apple-system, "Helvetica Neue", Arial, sans-serif';
const SERIF = '"Bodoni 72", Didot, Georgia, serif';

const outBack = (t: number) => 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
const outCubic = (t: number) => 1 - Math.pow(1 - t, 3);

/**
 * 홈 마무리 3D 장면. 장 다섯 개를 스크롤 위치로 그린다.
 * 0) 떠오름: 티켓, 응원봉, 포토카드, 칩이 튀어나와 공중에 뜬다. 1) 하나씩: 물건이 차례로 앞으로 나와 한 바퀴 돈다.
 * 2) 상자: 뚜껑이 열린 상자가 올라오고 물건이 자기 칸 위로 간다. 3) 담기: 하나씩 칸에 떨어진다.
 * 4) 닫힘: 뚜껑이 닫히고 FANQ 아이콘 위로 빛이 지나간다.
 * solo(0~3)를 주면 도메인 페이지용 짧은 판이 된다. 나머지 셋은 이미 칸에 들어 있고, 그 물건 하나만 떨어진 뒤 닫힌다.
 * 이때 장은 둘이다. 0) 상자가 올라오고 물건이 담긴다. 1) 닫힘.
 * 모든 모델과 무늬는 코드로 만든다. 밖에서 불러오는 것은 뚜껑의 아이콘 파일 하나다.
 */
export default function BoxFinale({ state, solo }: { state: MutableRefObject<FinaleState>; solo?: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current!;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
    } catch {
      setFailed(true);
      return;
    }
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;

    const scene = new THREE.Scene();
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envTex = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
    scene.environment = envTex;
    scene.environmentIntensity = 0.75;
    const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 200);

    scene.add(new THREE.HemisphereLight(0xcfc4ff, 0x0d0a1c, 0.5));
    const sun = new THREE.DirectionalLight(0xffffff, 2.2);
    sun.position.set(3.5, 9, 5);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    sun.shadow.camera.left = -6;
    sun.shadow.camera.right = 6;
    sun.shadow.camera.top = 6;
    sun.shadow.camera.bottom = -6;
    sun.shadow.camera.far = 30;
    sun.shadow.bias = -0.0005;
    sun.shadow.radius = 5;
    scene.add(sun);
    const rimA = new THREE.PointLight(0xff7a93, 26, 16);
    rimA.position.set(-5, 2.5, -3);
    scene.add(rimA);
    const rimB = new THREE.PointLight(0x5ad1ff, 22, 16);
    rimB.position.set(5.5, 1.5, -2.5);
    scene.add(rimB);

    const trash: { dispose: () => void }[] = [envTex, pmrem];
    const keep = <X extends { dispose: () => void }>(x: X) => {
      trash.push(x);
      return x;
    };
    const tex = (w: number, h: number, draw: (g: CanvasRenderingContext2D) => void) => {
      const c = document.createElement("canvas");
      c.width = w;
      c.height = h;
      draw(c.getContext("2d")!);
      const t = keep(new THREE.CanvasTexture(c));
      t.colorSpace = THREE.SRGBColorSpace;
      t.anisotropy = 8;
      return t;
    };
    /** 도형 좌표를 그대로 UV로 쓰는 돌출 도형에 무늬 한 장을 꽉 채운다 */
    const fitShape = (t: THREE.Texture, w: number, h: number) => {
      t.repeat.set(1 / w, 1 / h);
      t.offset.set(0.5, 0.5);
      return t;
    };
    const rounded = (w: number, h: number, r: number) => {
      const s = new THREE.Shape();
      s.moveTo(-w / 2 + r, -h / 2);
      s.lineTo(w / 2 - r, -h / 2);
      s.absarc(w / 2 - r, -h / 2 + r, r, -Math.PI / 2, 0, false);
      s.lineTo(w / 2, h / 2 - r);
      s.absarc(w / 2 - r, h / 2 - r, r, 0, Math.PI / 2, false);
      s.lineTo(-w / 2 + r, h / 2);
      s.absarc(-w / 2 + r, h / 2 - r, r, Math.PI / 2, Math.PI, false);
      s.lineTo(-w / 2, -h / 2 + r);
      s.absarc(-w / 2 + r, -h / 2 + r, r, Math.PI, Math.PI * 1.5, false);
      return s;
    };
    const mesh = (g: THREE.BufferGeometry, m: THREE.Material | THREE.Material[], shadow = true) => {
      keep(g);
      const o = new THREE.Mesh(g, m);
      o.castShadow = shadow;
      o.receiveShadow = shadow;
      return o;
    };
    const std = (p: THREE.MeshStandardMaterialParameters) => keep(new THREE.MeshStandardMaterial(p));
    const phys = (p: THREE.MeshPhysicalMaterialParameters) => keep(new THREE.MeshPhysicalMaterial(p));
    const basic = (p: THREE.MeshBasicMaterialParameters) => keep(new THREE.MeshBasicMaterial(p));

    const world = new THREE.Group();
    scene.add(world);

    // 바닥 그림자만 받는 투명한 판
    const groundMat = keep(new THREE.ShadowMaterial({ opacity: 0 }));
    const ground = mesh(new THREE.PlaneGeometry(40, 40), groundMat);
    ground.rotation.x = -Math.PI / 2;
    ground.castShadow = false;
    world.add(ground);

    // ── 물건 네 개 ──────────────────────────
    const objs: THREE.Group[] = [];

    // 1) 공연 티켓: 양쪽에 반원 홈이 파인 얇은 판
    {
      const w = 1.3;
      const h = 0.62;
      const r = 0.06;
      const nx = 0.33;
      const nr = 0.05;
      const s = new THREE.Shape();
      s.moveTo(-w / 2 + r, -h / 2);
      s.lineTo(nx - nr, -h / 2);
      s.absarc(nx, -h / 2, nr, Math.PI, 0, true);
      s.lineTo(w / 2 - r, -h / 2);
      s.absarc(w / 2 - r, -h / 2 + r, r, -Math.PI / 2, 0, false);
      s.lineTo(w / 2, h / 2 - r);
      s.absarc(w / 2 - r, h / 2 - r, r, 0, Math.PI / 2, false);
      s.lineTo(nx + nr, h / 2);
      s.absarc(nx, h / 2, nr, 0, Math.PI, true);
      s.lineTo(-w / 2 + r, h / 2);
      s.absarc(-w / 2 + r, h / 2 - r, r, Math.PI / 2, Math.PI, false);
      s.lineTo(-w / 2, -h / 2 + r);
      s.absarc(-w / 2 + r, -h / 2 + r, r, Math.PI, Math.PI * 1.5, false);
      const face = fitShape(
        tex(1040, 496, (g) => {
          const grd = g.createLinearGradient(0, 0, 1040, 496);
          grd.addColorStop(0, "#0d1030");
          grd.addColorStop(0.6, "#182766");
          grd.addColorStop(1, "#0f1a45");
          g.fillStyle = grd;
          g.fillRect(0, 0, 1040, 496);
          const foil = g.createLinearGradient(0, 0, 780, 0);
          foil.addColorStop(0, "#b7a4ff");
          foil.addColorStop(0.35, "#7db4ff");
          foil.addColorStop(0.7, "#a5f2d3");
          foil.addColorStop(1, "#ff9aae");
          g.fillStyle = foil;
          g.fillRect(56, 428, 660, 10);
          g.fillStyle = "rgba(255,255,255,0.06)";
          for (let i = 0; i < 9; i++) g.fillRect(0, 60 + i * 46, 780, 1);
          g.fillStyle = "#fff";
          g.font = `700 30px ${FONT}`;
          g.fillText("ADMIT ONE", 56, 92);
          g.font = `900 172px ${FONT}`;
          g.fillText("LIVE", 48, 300);
          g.font = `700 34px ${FONT}`;
          g.fillText("FANQ CONCERT  ROW A  SEAT 12", 56, 392);
          const cut = ((nx / w) + 0.5) * 1040;
          g.setLineDash([14, 12]);
          g.strokeStyle = "rgba(255,255,255,0.7)";
          g.lineWidth = 4;
          g.beginPath();
          g.moveTo(cut, 50);
          g.lineTo(cut, 446);
          g.stroke();
          g.setLineDash([]);
          for (let i = 0, x = cut + 50; x < 990; i++) {
            const bw = [6, 3, 9, 4, 5][i % 5];
            g.fillRect(x, 90, bw, 316);
            x += bw + [5, 8, 4][i % 3];
          }
        }),
        w,
        h,
      );
      const geo = new THREE.ExtrudeGeometry(s, { depth: 0.028, bevelEnabled: true, bevelSize: 0.006, bevelThickness: 0.006, bevelSegments: 2, curveSegments: 16 });
      geo.translate(0, 0, -0.014);
      const m = mesh(geo, [phys({ map: face, roughness: 0.42, metalness: 0.15, clearcoat: 0.6, clearcoatRoughness: 0.25 }), std({ color: 0x1a1f4d, roughness: 0.5 })]);
      const grp = new THREE.Group();
      grp.add(m);
      objs.push(grp);
    }

    // 2) 드롭 응원봉: 손잡이, 빛나는 Q, 유리 돔
    {
      const grp = new THREE.Group();
      const body = phys({ color: 0x17161f, roughness: 0.3, metalness: 0.3, clearcoat: 1, clearcoatRoughness: 0.15 });
      const chrome = phys({ color: 0xe8e6f0, metalness: 1, roughness: 0.14 });
      const glowMat = std({ color: 0xffffff, emissive: 0xb7a4ff, emissiveIntensity: 2.6, roughness: 0.4 });
      const handle = mesh(new THREE.CylinderGeometry(0.085, 0.075, 0.78, 32), body);
      handle.position.y = -0.3;
      const cap = mesh(new THREE.CylinderGeometry(0.095, 0.085, 0.07, 32), chrome);
      cap.position.y = -0.72;
      const collar = mesh(new THREE.TorusGeometry(0.1, 0.022, 16, 48), phys({ color: 0xb7a4ff, metalness: 0.9, roughness: 0.2, emissive: 0x6a57f2, emissiveIntensity: 0.6 }));
      collar.rotation.x = Math.PI / 2;
      collar.position.y = 0.1;
      const q = mesh(new THREE.TorusGeometry(0.13, 0.03, 16, 48), glowMat, false);
      q.position.y = 0.4;
      const tail = mesh(new THREE.CapsuleGeometry(0.025, 0.08, 6, 12), glowMat, false);
      tail.position.set(0.1, 0.29, 0);
      tail.rotation.z = Math.PI / 4;
      const dome = mesh(new THREE.SphereGeometry(0.31, 48, 32), phys({ color: 0xffffff, transparent: true, opacity: 0.26, roughness: 0.04, clearcoat: 1, depthWrite: false }));
      dome.position.y = 0.4;
      grp.add(handle, cap, collar, q, tail, dome);
      objs.push(grp);
    }

    // 3) 팬미팅 포토카드: 카드 위에 도톰한 하트
    {
      const w = 0.8;
      const h = 1.1;
      const face = fitShape(
        tex(560, 770, (g) => {
          const grd = g.createLinearGradient(0, 0, 560, 770);
          grd.addColorStop(0, "#3b1030");
          grd.addColorStop(0.55, "#8f2456");
          grd.addColorStop(1, "#2a0d2e");
          g.fillStyle = grd;
          g.fillRect(0, 0, 560, 770);
          g.strokeStyle = "rgba(255,255,255,0.75)";
          g.lineWidth = 4;
          g.strokeRect(34, 34, 492, 702);
          g.fillStyle = "rgba(255,255,255,0.16)";
          for (let i = 0; i < 6; i++) {
            g.beginPath();
            g.arc(280, 330, 70 + i * 44, 0, Math.PI * 2);
            g.lineWidth = 3;
            g.strokeStyle = "rgba(255,255,255,0.22)";
            g.stroke();
          }
          g.fillStyle = "#fff";
          g.textAlign = "center";
          g.font = `900 46px ${FONT}`;
          g.fillText("FAN MEETING", 280, 660);
          g.font = `700 24px ${FONT}`;
          g.fillText("FANQ  LUCKY DRAW", 280, 700);
        }),
        w,
        h,
      );
      const geo = new THREE.ExtrudeGeometry(rounded(w, h, 0.07), { depth: 0.024, bevelEnabled: true, bevelSize: 0.006, bevelThickness: 0.006, bevelSegments: 2, curveSegments: 16 });
      geo.translate(0, 0, -0.012);
      const card = mesh(geo, [phys({ map: face, roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.12 }), std({ color: 0xffffff, roughness: 0.5 })]);
      const hs = new THREE.Shape();
      hs.moveTo(5, 5);
      hs.bezierCurveTo(5, 5, 4, 0, 0, 0);
      hs.bezierCurveTo(-6, 0, -6, 7, -6, 7);
      hs.bezierCurveTo(-6, 11, -3, 15.4, 5, 19);
      hs.bezierCurveTo(12, 15.4, 16, 11, 16, 7);
      hs.bezierCurveTo(16, 7, 16, 0, 10, 0);
      hs.bezierCurveTo(7, 0, 5, 5, 5, 5);
      const hg = new THREE.ExtrudeGeometry(hs, { depth: 2.4, bevelEnabled: true, bevelSize: 1.6, bevelThickness: 1.6, bevelSegments: 8, curveSegments: 24 });
      hg.center();
      hg.rotateZ(Math.PI);
      hg.scale(0.017, 0.017, 0.017);
      const heart = mesh(hg, phys({ color: 0xff3d6e, roughness: 0.18, clearcoat: 1, clearcoatRoughness: 0.05, emissive: 0xff2a5a, emissiveIntensity: 0.25 }));
      heart.position.set(0, 0.1, 0.07);
      const grp = new THREE.Group();
      grp.add(card, heart);
      objs.push(grp);
    }

    // 4) 경매 칩: 옆면 줄무늬와 Q가 새겨진 앞뒤 면
    {
      const side = tex(512, 16, (g) => {
        for (let i = 0; i < 8; i++) {
          g.fillStyle = "#c81f35";
          g.fillRect(i * 64, 0, 44, 16);
          g.fillStyle = "#f6efe1";
          g.fillRect(i * 64 + 44, 0, 20, 16);
        }
      });
      const face = tex(512, 512, (g) => {
        const R = 256;
        g.translate(R, R);
        g.fillStyle = "#d4243a";
        g.beginPath();
        g.arc(0, 0, R, 0, Math.PI * 2);
        g.fill();
        g.fillStyle = "#f6efe1";
        for (let i = 0; i < 8; i++) {
          const a = (i / 8) * Math.PI * 2;
          g.beginPath();
          g.arc(0, 0, R, a - 0.15, a + 0.15);
          g.arc(0, 0, R * 0.78, a + 0.15, a - 0.15, true);
          g.closePath();
          g.fill();
        }
        g.strokeStyle = "#f6efe1";
        g.lineWidth = 7;
        g.beginPath();
        g.arc(0, 0, R * 0.72, 0, Math.PI * 2);
        g.stroke();
        g.fillStyle = "#a81628";
        g.beginPath();
        g.arc(0, 0, R * 0.66, 0, Math.PI * 2);
        g.fill();
        g.setLineDash([10, 9]);
        g.lineWidth = 3;
        g.beginPath();
        g.arc(0, 0, R * 0.52, 0, Math.PI * 2);
        g.stroke();
        g.setLineDash([]);
        g.fillStyle = "#f6efe1";
        g.textAlign = "center";
        g.textBaseline = "middle";
        g.font = `700 190px ${SERIF}`;
        g.fillText("Q", 0, 14);
        g.font = `700 24px ${FONT}`;
        g.fillText("FANQ AUCTION", 0, -104);
      });
      const grp = new THREE.Group();
      const edge = mesh(new THREE.CylinderGeometry(0.5, 0.5, 0.1, 64, 1, true), std({ map: side, roughness: 0.45 }));
      edge.rotation.x = Math.PI / 2;
      const faceMat = phys({ map: face, roughness: 0.35, clearcoat: 0.6, clearcoatRoughness: 0.25 });
      const front = mesh(new THREE.CircleGeometry(0.5, 64), faceMat);
      front.position.z = 0.05;
      const back = mesh(new THREE.CircleGeometry(0.5, 64), faceMat);
      back.position.z = -0.05;
      back.rotation.y = Math.PI;
      grp.add(edge, front, back);
      objs.push(grp);
    }
    objs.forEach((o) => world.add(o));

    // 칸 안에 누운 자세와 바닥에서 뜬 높이
    const qFlat = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), -Math.PI / 2);
    const yAxis = new THREE.Vector3(0, 1, 0);
    const flat = [0.1, 0.9, -0.14, 0.3].map((yaw) => new THREE.Quaternion().setFromAxisAngle(yAxis, yaw).multiply(qFlat));
    const restY = [T + 0.03, T + 0.32, T + 0.03, T + 0.06];

    // ── 상자 ────────────────────────────────
    const boxG = new THREE.Group();
    world.add(boxG);
    const matte = std({ color: 0x0d0c13, roughness: 0.8, metalness: 0.15 });
    const lining = std({ color: 0x1c1536, roughness: 1 });
    const metal = phys({ color: 0x8179b8, metalness: 1, roughness: 0.32 });
    const put = (o: THREE.Object3D, x: number, y: number, z: number, parent: THREE.Object3D = boxG) => {
      o.position.set(x, y, z);
      parent.add(o);
      return o;
    };
    put(mesh(new THREE.BoxGeometry(W, T, D), matte), 0, T / 2, 0);
    const floor = put(mesh(new THREE.PlaneGeometry(W - 2 * T, D - 2 * T), lining), 0, T + 0.002, 0);
    floor.rotation.x = -Math.PI / 2;
    put(mesh(new THREE.BoxGeometry(W, H, T), matte), 0, H / 2, D / 2 - T / 2);
    put(mesh(new THREE.BoxGeometry(W, H, T), matte), 0, H / 2, -D / 2 + T / 2);
    put(mesh(new THREE.BoxGeometry(T, H, D - 2 * T), matte), W / 2 - T / 2, H / 2, 0);
    put(mesh(new THREE.BoxGeometry(T, H, D - 2 * T), matte), -W / 2 + T / 2, H / 2, 0);
    put(mesh(new THREE.BoxGeometry(W - 2 * T, 0.42, 0.024), metal), 0, T + 0.21, 0);
    put(mesh(new THREE.BoxGeometry(0.024, 0.42, D - 2 * T), metal), 0, T + 0.21, 0);

    // 옆면을 두르는 FANQ 그라디언트 띠와 글자
    const bandTex = tex(512, 8, (g) => {
      const grd = g.createLinearGradient(0, 0, 512, 0);
      grd.addColorStop(0, "#7b61ff");
      grd.addColorStop(0.4, "#3d8bff");
      grd.addColorStop(0.72, "#1fb89a");
      grd.addColorStop(1, "#ff7a93");
      g.fillStyle = grd;
      g.fillRect(0, 0, 512, 8);
    });
    const bandMat = basic({ map: bandTex, toneMapped: false });
    const band = (w: number, x: number, z: number, ry: number) => {
      const b = put(mesh(new THREE.PlaneGeometry(w, 0.07), bandMat, false), x, 0.2, z);
      b.rotation.y = ry;
    };
    band(W, 0, D / 2 + 0.002, 0);
    band(W, 0, -D / 2 - 0.002, Math.PI);
    band(D, W / 2 + 0.002, 0, Math.PI / 2);
    band(D, -W / 2 - 0.002, 0, -Math.PI / 2);
    const wordTex = tex(768, 192, (g) => {
      g.fillStyle = "#f4f0e8";
      g.textBaseline = "middle";
      g.font = `900 132px ${FONT}`;
      let x = 20;
      for (const ch of "FANQ") {
        g.fillText(ch, x, 100);
        x += g.measureText(ch).width + 26;
      }
    });
    put(mesh(new THREE.PlaneGeometry(1.0, 0.25), basic({ map: wordTex, transparent: true, toneMapped: false }), false), -W / 2 + 0.72, 0.49, D / 2 + 0.003);

    // 뚜껑: 뒤쪽 위 모서리를 축으로 돈다
    const lid = new THREE.Group();
    put(lid, 0, H, -D / 2 - 0.04);
    const LZ = D / 2 + 0.04;
    put(mesh(new THREE.BoxGeometry(W + 0.14, 0.07, D + 0.14), matte), 0, 0.035, LZ, lid);
    put(mesh(new THREE.BoxGeometry(W + 0.14, 0.16, 0.03), matte), 0, -0.045, D + 0.095, lid);
    put(mesh(new THREE.BoxGeometry(W + 0.14, 0.16, 0.03), matte), 0, -0.045, -0.015, lid);
    put(mesh(new THREE.BoxGeometry(0.03, 0.16, D + 0.14), matte), W / 2 + 0.055, -0.045, LZ, lid);
    put(mesh(new THREE.BoxGeometry(0.03, 0.16, D + 0.14), matte), -W / 2 - 0.055, -0.045, LZ, lid);
    const inner = put(mesh(new THREE.PlaneGeometry(1.7, 0.425), basic({ map: wordTex, transparent: true, opacity: 0.5, toneMapped: false }), false), 0, -0.002, LZ, lid);
    inner.rotation.x = Math.PI / 2;

    // 뚜껑 위 아이콘: 살짝 솟은 둥근 사각 판에 FANQ 아이콘을 올린다
    const ICON = 1.3;
    let iconCtx: CanvasRenderingContext2D | null = null;
    const iconTex = fitShape(
      tex(512, 512, (g) => {
        iconCtx = g;
        g.fillStyle = "#0b0d2a";
        g.fillRect(0, 0, 512, 512);
      }),
      ICON,
      ICON,
    );
    const img = new Image();
    img.onload = () => {
      iconCtx?.drawImage(img, 0, 0, 512, 512);
      iconTex.needsUpdate = true;
    };
    img.src = "/fanq-icon.svg";
    const iconGeo = new THREE.ExtrudeGeometry(rounded(ICON, ICON, (ICON * 14) / 64), { depth: 0.03, bevelEnabled: true, bevelSize: 0.008, bevelThickness: 0.008, bevelSegments: 3, curveSegments: 24 });
    const icon = put(mesh(iconGeo, [phys({ map: iconTex, roughness: 0.28, clearcoat: 1, clearcoatRoughness: 0.08 }), metal]), 0, 0.07, LZ, lid);
    icon.rotation.x = -Math.PI / 2;
    // 아이콘 위를 쓸고 지나가는 빛
    const sweepTex = tex(256, 256, (g) => {
      const grd = g.createLinearGradient(60, 0, 196, 136);
      grd.addColorStop(0, "rgba(255,255,255,0)");
      grd.addColorStop(0.5, "rgba(255,255,255,0.95)");
      grd.addColorStop(1, "rgba(255,255,255,0)");
      g.fillStyle = grd;
      g.fillRect(0, 0, 256, 256);
    });
    const sweepMat = basic({ map: sweepTex, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false });
    const sweep = put(mesh(new THREE.PlaneGeometry(ICON * 0.96, ICON * 0.96), sweepMat, false), 0, 0.118, LZ, lid);
    sweep.rotation.x = -Math.PI / 2;
    // 뚜껑 앞쪽의 작은 글자
    const lidWord = put(mesh(new THREE.PlaneGeometry(0.8, 0.2), basic({ map: wordTex, transparent: true, opacity: 0.6, toneMapped: false }), false), 0, 0.072, LZ + D / 2 - 0.3, lid);
    lidWord.rotation.x = -Math.PI / 2;
    const stamp = new THREE.PointLight(0xb7a4ff, 0, 8);
    put(stamp, 0, H + 1.4, 0.4);

    // ── 화면 크기 ───────────────────────────
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
    window.addEventListener("resize", resize);

    const tanHalf = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
    const v = (x: number, y: number, z: number) => new THREE.Vector3(x, y, z);
    /** 필요한 가로 폭이 화면에 다 들어오도록 카메라를 목표에서 뒤로 물린다 */
    const fit = (pos: THREE.Vector3, target: THREE.Vector3, need: number) => {
      const dir = pos.clone().sub(target);
      const k = Math.max(1, need / (tanHalf * camera.aspect * dir.length()));
      return target.clone().add(dir.multiplyScalar(k));
    };
    const camNow = v(0, 1.9, 9);
    const lookNow = v(0, 1.7, 0);
    let live = false;
    let raf = 0;
    const qUp = new THREE.Quaternion();
    const qTmp = new THREE.Quaternion();
    const F = v(0, 0, 0);
    const Hv = v(0, 0, 0);
    const P = v(0, 0, 0);

    const frame = (now: number) => {
      raf = requestAnimationFrame(frame);
      const st = state.current;
      if (st.index < 0) {
        if (live) {
          live = false;
          canvas.style.opacity = "0";
        }
        return;
      }
      const p = (k: number) => (st.index > k ? 1 : st.index === k ? st.local : 0);
      const one = solo !== undefined;
      const l0 = p(0);
      const p0 = one ? 1 : l0;
      const p1 = one ? 1 : p(1);
      const p2 = one ? ramp(l0, 0, 0.5) : p(2);
      const p3 = one ? 0 : p(3);
      const p4 = one ? p(1) : p(4);
      const soloDrop = one ? ramp(l0, 0.58, 0.82) : 0;
      const wide = !one && w >= 900 && w > h;
      const time = reduce ? 0 : now;
      canvas.style.opacity = String(st.index === 0 && !one ? ramp(p0, 0, 0.12) : 1);

      const close = smooth(ramp(p4, 0, 0.6));
      const end = smooth(ramp(p4, 0.62, 0.95));
      const shift = wide ? 1.45 * (1 - smooth(ramp(p4, 0.1, 0.6))) : 0;
      world.position.x = shift;
      world.rotation.y = -0.3 * smooth(ramp(p2, 0, 0.6)) * (1 - close) + Math.sin(time / 2600) * 0.2 * end;

      // 상자: 아래에서 올라오고, 물건이 떨어질 때마다 살짝 눌린다
      boxG.visible = p2 > 0;
      let jig = 0;
      const landAt = (i: number) => 0.06 + i * 0.21 + 0.2;
      for (let i = 0; i < 4; i++) {
        const b = ramp(p3, landAt(i), landAt(i) + 0.07);
        jig += Math.sin(b * Math.PI) * 0.035;
      }
      if (one) jig += Math.sin(ramp(l0, 0.82, 0.9) * Math.PI) * 0.045;
      const thud = ramp(p4, 0.5, 0.62);
      boxG.position.y = mix(-7, 0, outCubic(ramp(p2, 0, 0.62))) - jig;
      boxG.scale.y = 1 - Math.sin(thud * Math.PI) * 0.035;
      lid.rotation.x = mix(LID_OPEN, 0, Math.pow(ramp(p4, 0.05, 0.5), 3));
      const sw = ramp(p4, 0.56, 0.88);
      sweepTex.offset.x = mix(-0.85, 0.85, sw);
      sweepMat.opacity = Math.sin(sw * Math.PI);
      stamp.intensity = Math.sin(ramp(p4, 0.5, 0.85) * Math.PI) * 34;
      icon.position.y = 0.07 - Math.sin(thud * Math.PI) * 0.02;

      // 물건 네 개
      const sub = Math.min(3, Math.floor(p1 * 4));
      const u = p1 * 4 - sub;
      const focus = p1 > 0 && p1 < 1 ? smooth(ramp(u, 0, 0.25)) * (1 - smooth(ramp(u, 0.8, 1))) : 0;
      const hover = smooth(ramp(p2, 0.25, 0.95));
      objs.forEach((o, i) => {
        if (one && i !== solo) {
          // 이미 담겨 있는 물건: 상자와 함께 움직인다
          o.position.set(CELLS[i][0], restY[i] + boxG.position.y, CELLS[i][1]);
          o.quaternion.copy(flat[i]);
          o.scale.setScalar(1);
          o.visible = p2 > 0 && p4 < 0.5;
          return;
        }
        const a = outBack(ramp(p0, 0.15 + i * 0.12, 0.5 + i * 0.12));
        const bob = Math.sin(time / 900 + i * 1.7) * 0.06 * (1 - hover);
        if (wide) F.set((i - 1.5) * 1.12, 1.75 + bob, 0);
        else F.set(i % 2 ? 0.78 : -0.78, (i < 2 ? 2.55 : 1.0) + bob, 0);
        F.y -= (1 - Math.min(1, a)) * 0.7;
        const g = i === sub ? focus : 0;
        P.copy(F).lerp(v(0, 1.8, 2.3), g);
        Hv.set(CELLS[i][0], 2.4, CELLS[i][1]);
        P.lerp(Hv, hover);
        if (one) P.set(CELLS[i][0], 2.6 + Math.sin(time / 800) * 0.07, CELLS[i][1]);
        // 떨어지기: 점점 빨라지다가 닿으면 한 번 튄다
        const s0 = 0.06 + i * 0.21;
        const drop = one ? soloDrop : ramp(p3, s0, s0 + 0.2);
        const rest = restY[i] + boxG.position.y;
        P.y = mix(P.y, rest, drop * drop);
        const bnc = one ? ramp(l0, 0.82, 0.94) : ramp(p3, s0 + 0.2, s0 + 0.3);
        P.y += Math.sin(bnc * Math.PI) * 0.11 * (1 - bnc);
        o.position.copy(P);
        const yaw = (1 - Math.min(1, a)) * -2.4 + (i === sub && p1 > 0 && p1 < 1 ? smooth(u) * Math.PI * 2 : 0) + Math.sin(time / 1700 + i) * 0.22 * (1 - hover);
        qUp.setFromAxisAngle(yAxis, yaw);
        if (one) qUp.setFromAxisAngle(yAxis, time / 1400);
        qTmp.copy(qUp).slerp(flat[i], one ? smooth(drop) : Math.min(1, hover * 0.55 + drop * 0.45));
        o.quaternion.copy(qTmp);
        o.scale.setScalar(Math.max(0.0001, a) * (1 + 0.38 * g) * (1 - (focus - g) * 0.1) * (one ? 1 + 0.5 * (1 - smooth(drop)) : 1));
        o.visible = a > 0 && p4 < 0.5;
      });

      // 카메라: 정면에서 시작해 상자를 내려다보고, 닫힐 때 뚜껑 위로 간다
      const lift = wide ? 0 : 0.9 * (1 - close);
      const tF = v(0, 1.7 + lift, 0);
      const tB = v(0, 1.25 + lift, -0.3);
      const tC = v(0, wide || (one && w > h) ? -1.3 : -0.4, 0.2);
      const cF = fit(v(0, 1.9 + lift, 9), tF, shift + (wide ? 2.4 : 1.7));
      const cB = fit(v(0, 6.6 + lift, 8.2), tB, shift + (wide ? 3.9 : one && w > h ? 5.4 : 3.1));
      const cC = fit(v(0, 9.4, 6.2), tC, wide ? 3.4 : one && w > h ? 4.4 : 2.6);
      const toBox = smooth(ramp(p2, 0.05, 0.85));
      groundMat.opacity = 0.42 * toBox;
      const cam = cF.lerp(cB, toBox).lerp(cC, close);
      const look = tF.lerp(tB, toBox).lerp(tC, close);
      if (!live) {
        live = true;
        camNow.copy(cam);
        lookNow.copy(look);
      } else {
        camNow.lerp(cam, 0.14);
        lookNow.lerp(look, 0.14);
      }
      camera.position.copy(camNow);
      camera.lookAt(lookNow);
      renderer.render(scene, camera);
    };
    raf = requestAnimationFrame(frame);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      img.onload = null;
      trash.forEach((x) => x.dispose());
      renderer.dispose();
    };
  }, [state, solo]);

  if (failed) return null;
  return <canvas ref={canvasRef} className="fq-orbit-finale" style={{ opacity: 0 }} aria-hidden="true" />;
}
