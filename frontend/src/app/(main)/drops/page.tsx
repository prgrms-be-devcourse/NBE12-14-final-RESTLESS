"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import DomainBox from "@/components/finale/DomainBox";
import DropShow from "@/components/dropshow/DropShow";
import { IntroGate, useIntro } from "@/lib/intro";
import { useEffect, useRef, useState } from "react";
import { firstWord } from "@/components/Cards";
import ChapterHud from "@/components/chapter/ChapterHud";
import LoadError from "@/components/LoadError";
import Poster, { PALETTES } from "@/components/Poster";
import Tabs from "@/components/Tabs";
import { api } from "@/lib/api";
import { ramp, smooth } from "@/lib/chapters";
import { countdown, dateOnly, dateTime, msLeft } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import { useScrollProgress } from "@/lib/scroll";
import type { DropItem, DropPage } from "@/lib/types";
import "./drops.css";

type F = "ALL" | "ON_SALE" | "UPCOMING" | "ENDED";
type State = Exclude<F, "ALL">;
type Row = { d: DropItem; s: State };

/** 서버 상태와 시각으로 지금 상태를 정한다. 기간이 지나면 서버 값보다 시각을 따른다 */
function stateOf(d: DropItem, now: number): State {
  if (["ENDED", "CLOSED", "SOLD_OUT"].includes(d.saleStatus) || msLeft(d.closesAt, now) <= 0) return "ENDED";
  if (["UPCOMING", "SCHEDULED"].includes(d.saleStatus) || msLeft(d.opensAt, now) > 0) return "UPCOMING";
  return "ON_SALE";
}
/** 포스터에 크게 쓸 단어. 아티스트와 투어 이름을 빼고 상품 이름을 고른다 */
function productWord(title: string) {
  const words = (title.split(">").pop() ?? title).trim().split(/\s+/).filter((w) => !["공식", "투어", "한정판", "세트"].includes(w));
  return words[0] ?? firstWord(title);
}
const ORDER: Record<State, number> = { ON_SALE: 0, UPCOMING: 1, ENDED: 2 };
const colorOf = (dropId: number) => PALETTES[(dropId + 1) % PALETTES.length];

/**
 * 드롭 목록. 장 네 개로 이어지는 긴 스크롤 화면이다.
 * 1) 커튼: 화면이 고정된 채 물결 커튼이 끝까지 내려오고 제목이 커졌다가 걷힌다.
 * 2) 진열대: 카드마다 멈춰 서고, 멈춘 동안 무대가 그 카드 색으로 물들며 뒤로 큰 상품 이름이 흐른다.
 * 3) 다음 드롭: 오픈 예정 드롭을 화면 가득한 카운트다운으로 보여 준다.
 * 4) 지난 드롭: 끝난 드롭을 흑백으로 모은다.
 */
export default function DropsPage() {
  const now = useNow();
  const [f, setF] = useState<F>("ALL");
  const { data, error } = useLoad(() => api<DropPage>("/api/drops").then((r) => r.items));
  const all: Row[] = (data ?? []).map((d) => ({ d, s: stateOf(d, now) })).sort((a, b) => ORDER[a.s] - ORDER[b.s] || +new Date(a.d.opensAt) - +new Date(b.d.opensAt));
  // 전체 보기에서는 끝난 드롭을 진열대 대신 아래 지난 드롭 장에 모은다
  const rail = f === "ALL" ? all.filter((x) => x.s !== "ENDED") : all.filter((x) => x.s === f);
  const ended = all.filter((x) => x.s === "ENDED");
  const next = all.find((x) => x.s === "UPCOMING");
  const count = (x: F) => (data ? (x === "ALL" ? all.length : all.filter((y) => y.s === x).length) : undefined);
  const live = count("ON_SALE") ?? 0;

  return (
    <div className="fq-dl">
      <IntroGate>
        <Curtain live={data ? live : null} />
        <DropShow skipTo="#drops-list" />
      </IntroGate>

      <div id="drops-list" className="fq-dl-tabs">
        <Tabs
          label="드롭 필터"
          value={f}
          onChange={setF}
          items={[
            { value: "ALL", label: "전체", count: count("ALL") },
            { value: "ON_SALE", label: "판매 중", count: count("ON_SALE") },
            { value: "UPCOMING", label: "오픈 예정", count: count("UPCOMING") },
            { value: "ENDED", label: "종료", count: count("ENDED") },
          ]}
        />
      </div>

      {error ? (
        <div className="fq-section">
          <LoadError error={error} />
        </div>
      ) : !data ? (
        <div className="fq-skel" style={{ height: 520, marginTop: 22 }} />
      ) : rail.length === 0 ? (
        <div className="fq-card is-pad fq-empty" style={{ marginTop: 22 }}>
          <LineArt kind="box" />
          <p>해당하는 드롭이 없어요.</p>
        </div>
      ) : (
        <Rail key={f} items={rail} now={now} />
      )}

      <div id="drops-after" />
      {f === "ALL" && next ? <NextDrop row={next} now={now} /> : null}
      {f === "ALL" && ended.length ? <Archive rows={ended} /> : null}
      <DomainBox domain="drop" />
    </div>
  );
}

/** 1장. 고정된 화면에서 커튼이 내려오고, 제목이 커졌다가, 커튼이 위로 걷힌다 */
function Curtain({ live }: { live: number | null }) {
  const ref = useRef<HTMLElement>(null);
  useScrollProgress(ref);
  return (
    <section ref={ref} className="fq-dl-curtain">
      <div className="fq-dl-curtain-stage">
        <div className="fq-dl-curtain-cloth">
          <div className="fq-dl-curtain-body">
            <div className="fq-dl-headline">
              <span className="fq-dl-word">EXPLORE ALL</span>
              <Seal />
              <span className="fq-dl-word">DROPS</span>
            </div>
            <p className="fq-dl-lead">
              수량이 정해진 한정판 굿즈예요. 대기열 순서대로 구매할 수 있어요.
              {live !== null ? <b> 지금 {live}개 판매 중.</b> : null}
            </p>
          </div>
          <div className="fq-dl-scallop" aria-hidden="true" />
        </div>
        <span className="fq-dl-curtain-hint" aria-hidden="true">
          아래로 스크롤하세요
        </span>
      </div>
    </section>
  );
}

/** 글자가 원을 따라 도는 인장 */
function Seal() {
  return (
    <span className="fq-dl-seal" aria-hidden="true">
      <svg viewBox="0 0 120 120">
        <defs>
          <path id="fq-dl-seal-path" d="M60,60 m-46,0 a46,46 0 1,1 92,0 a46,46 0 1,1 -92,0" />
        </defs>
        <text>
          <textPath href="#fq-dl-seal-path">LIMITED ✦ ONE TIME ONLY ✦ FANQ DROP ✦</textPath>
        </text>
      </svg>
      <i>Q</i>
    </span>
  );
}

/**
 * 2장. 넓은 화면에서는 세로 스크롤을 가로 이동으로 바꾸고, 카드마다 앞뒤 30%는 멈춰 선다.
 * 좁은 화면에서는 손으로 넘기는 가로 목록이 된다.
 */
function Rail({ items, now }: { items: Row[]; now: number }) {
  const rootRef = useRef<HTMLElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);
  // 연출을 끄면 넓은 화면에서도 스크롤을 가로 이동으로 바꾸지 않고, 손으로 넘기는 가로 목록으로 둔다
  const flat = useIntro() === false;

  useEffect(() => {
    const root = rootRef.current!;
    const stage = stageRef.current!;
    const track = trackRef.current!;
    const mq = window.matchMedia("(min-width: 761px)");
    const wide = { get matches() { return !flat && mq.matches; } };
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const n = items.length;
    let target = 0;
    let shown = 0;
    let raf = 0;
    let cur = -1;
    // 카드 하나가 가운데 올 때의 트랙 이동 거리
    let stops: number[] = [];

    const measure = () => {
      const cards = Array.from(track.children) as HTMLElement[];
      if (!wide.matches) {
        root.style.height = "";
        track.style.transform = "";
        stops = [];
        return;
      }
      const mid = stage.clientWidth / 2;
      stops = cards.map((c) => c.offsetLeft + c.offsetWidth / 2 - mid);
      root.style.height = `${stage.offsetHeight + Math.max(1, n) * window.innerHeight * 1.1}px`;
    };
    const read = () => {
      const rect = root.getBoundingClientRect();
      const span = root.offsetHeight - stage.offsetHeight;
      target = span > 0 ? Math.min(1, Math.max(0, -rect.top / span)) : 0;
    };
    const frame = () => {
      raf = 0;
      shown += (target - shown) * 0.14;
      if (Math.abs(target - shown) < 0.0005) shown = target;
      root.style.setProperty("--p", shown.toFixed(4));
      if (wide.matches && stops.length) {
        // 카드마다 앞뒤 30%는 멈추고 가운데 40%에서 다음 카드로 미끄러진다
        const x = shown * (n - 1);
        const seg = Math.min(n - 2, Math.max(0, Math.floor(x)));
        const f = n > 1 ? smooth(ramp(x - seg, 0.3, 0.7)) : 0;
        const pos = n > 1 ? stops[seg] + (stops[seg + 1] - stops[seg]) * f : stops[0];
        track.style.transform = `translate3d(${-pos}px, 0, 0)`;
      }
      const mid = stage.getBoundingClientRect().left + stage.clientWidth / 2;
      let best = 0;
      let bestD = Infinity;
      (Array.from(track.children) as HTMLElement[]).forEach((el, i) => {
        const r = el.getBoundingClientRect();
        const d = (r.left + r.width / 2 - mid) / stage.clientWidth;
        el.style.setProperty("--d", reduce ? "0" : Math.max(-1, Math.min(1, d)).toFixed(3));
        if (Math.abs(d) < bestD) {
          bestD = Math.abs(d);
          best = i;
        }
      });
      if (best !== cur) {
        cur = best;
        setActive(best);
      }
      if (shown !== target) raf = requestAnimationFrame(frame);
    };
    const kick = () => {
      if (!raf) raf = requestAnimationFrame(frame);
    };
    const onScroll = () => {
      read();
      kick();
    };
    const onResize = () => {
      measure();
      read();
      kick();
    };
    measure();
    read();
    shown = target;
    kick();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    track.addEventListener("scroll", kick, { passive: true });
    mq.addEventListener("change", onResize);
    const ro = new ResizeObserver(onResize);
    ro.observe(track);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
      track.removeEventListener("scroll", kick);
      mq.removeEventListener("change", onResize);
      ro.disconnect();
      root.style.height = "";
    };
  }, [items.length, flat]);

  const a = items[Math.min(active, items.length - 1)];
  const [c1, c2] = colorOf(a.d.dropId);

  return (
    <section ref={rootRef} className={`fq-dl-rail${flat ? " is-flat" : ""}`} aria-label="드롭 목록" style={{ "--c1": c1, "--c2": c2 } as React.CSSProperties}>
      <div ref={stageRef} className="fq-dl-stage">
        <div className="fq-dl-wash" aria-hidden="true" />
        <div key={a.d.dropId} className="fq-dl-bigword" aria-hidden="true">
          <span>{`${productWord(a.d.title)} `.repeat(6)}</span>
        </div>
        <div ref={trackRef} className="fq-dl-track">
          {items.map(({ d, s }, i) => (
            <Link key={d.dropId} href={`/drops/${d.dropId}`} className={`fq-dl-card is-${s.toLowerCase()}${i === active ? " is-active" : ""}`} style={{ "--tilt": i % 2 ? 1 : -1 } as React.CSSProperties}>
              <span className="fq-dl-no fq-num" aria-hidden="true">
                {String(i + 1).padStart(2, "0")}
              </span>
              <div className="fq-dl-art">
                <Poster seed={d.dropId + 1} word={productWord(d.title)} kicker={`DROP #${String(d.dropId).padStart(3, "0")}`} imageUrl={d.imageUrl} ratio="4 / 5" />
              </div>
              <div className="fq-dl-info">
                {s === "ON_SALE" ? <span className="fq-chip is-live">판매 중</span> : s === "UPCOMING" ? <span className="fq-chip">오픈 예정</span> : <span className="fq-chip is-mute">판매 종료</span>}
                <h3>{d.title}</h3>
                <p className="fq-dl-when">
                  {s === "ON_SALE" ? (
                    <>
                      마감까지 <b className="fq-num">{countdown(d.closesAt, now)}</b>
                    </>
                  ) : s === "UPCOMING" ? (
                    <>
                      <b className="fq-num">{countdown(d.opensAt, now)}</b> 뒤 오픈 <small>{dateTime(d.opensAt)}</small>
                    </>
                  ) : (
                    <>{dateOnly(d.closesAt)} 종료</>
                  )}
                </p>
              </div>
            </Link>
          ))}
        </div>
        {flat ? null : <ChapterHud names={items.map((x) => productWord(x.d.title))} index={Math.min(active, items.length - 1)} skipTo="#drops-after" />}
      </div>
    </section>
  );
}

/** 3장. 다음 드롭을 화면 가득한 카운트다운으로 */
function NextDrop({ row, now }: { row: Row; now: number }) {
  const ref = useRef<HTMLElement>(null);
  useScrollProgress(ref, undefined, "enter");
  const left = countdown(row.d.opensAt, now);
  const [c1, c2] = colorOf(row.d.dropId);
  return (
    <section ref={ref} className="fq-dl-next" style={{ "--c1": c1, "--c2": c2 } as React.CSSProperties}>
      <span className="fq-dl-kicker">NEXT DROP</span>
      <h2>{productWord(row.d.title)}</h2>
      <div className="fq-dl-clock fq-num" aria-label={`오픈까지 ${left}`}>
        {left.split("").map((ch, i) => (
          <span key={`${i}-${ch}`} className={/\d/.test(ch) ? "is-digit" : undefined}>
            {ch}
          </span>
        ))}
      </div>
      <p>
        {row.d.title}
        <br />
        {dateTime(row.d.opensAt)} 오픈
      </p>
      <Link href={`/drops/${row.d.dropId}`} className="fq-dl-next-cta">
        상품 보기 →
      </Link>
    </section>
  );
}

/** 4장. 끝난 드롭을 흑백으로 모은다 */
function Archive({ rows }: { rows: Row[] }) {
  const ref = useRef<HTMLElement>(null);
  useScrollProgress(ref, undefined, "enter");
  return (
    <section ref={ref} className="fq-dl-archive">
      <span className="fq-dl-kicker">ARCHIVE</span>
      <h2>지난 드롭</h2>
      <div className="fq-dl-archive-grid">
        {rows.map(({ d }, i) => (
          <Link key={d.dropId} href={`/drops/${d.dropId}`} className="fq-dl-archive-card" style={{ animationDelay: `${i * 80}ms` }}>
            <Poster seed={d.dropId + 1} word={productWord(d.title)} kicker={`DROP #${String(d.dropId).padStart(3, "0")}`} imageUrl={d.imageUrl} ratio="1 / 1" />
            <b>{d.title}</b>
            <small>{dateOnly(d.closesAt)} 종료</small>
          </Link>
        ))}
      </div>
    </section>
  );
}
