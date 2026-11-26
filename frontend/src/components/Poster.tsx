"use client";

import { useState } from "react";
import "./Poster.css";

export const PALETTES = [
  ["#7B61FF", "#3D8BFF", "#1FB89A"],
  ["#FF7A93", "#B04FD0", "#7B61FF"],
  ["#FFB547", "#FF7A45", "#FF5A6E"],
  ["#22C3A6", "#0EA5E9", "#7B61FF"],
  ["#8B6CFF", "#FF7A93", "#FFD36E"],
  ["#0EA5E9", "#6A57F2", "#FF9AAE"],
];
const SHAPES = ["rings", "rays", "orbs", "grid"] as const;

/**
 * 공연 포스터, 상품 사진. imageUrl이 있으면 사진을 그대로 보여주고(사진 속 로고와 겹치지 않게 큰 글자는 숨긴다),
 * 없거나 못 불러오면 seed로 색과 모양을 고른 그래픽 포스터를 그린다.
 */
export default function Poster({
  seed,
  word,
  kicker,
  imageUrl,
  ratio = "4 / 5",
  className,
}: {
  seed: number;
  word: string;
  kicker?: string;
  imageUrl?: string | null;
  ratio?: string;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const src = imageUrl;
  const photo = !!src && !failed;
  const p = PALETTES[seed % PALETTES.length];
  const shape = SHAPES[seed % SHAPES.length];
  const style = {
    "--p1": p[0],
    "--p2": p[1],
    "--p3": p[2],
    ...(ratio === "auto" ? {} : { aspectRatio: ratio }),
  } as React.CSSProperties;

  return (
    <div className={`fq-poster is-${shape}${photo ? " is-photo" : ""}${className ? " " + className : ""}`} style={style} aria-hidden="true">
      {photo ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src!}
          alt=""
          loading="lazy"
          style={{ objectFit: "cover", objectPosition: "50% 50%" }}
          onError={() => setFailed(true)}
        />
      ) : (
        <>
          <div className="fq-poster-fx" />
          <div className="fq-poster-word">{word}</div>
        </>
      )}
      {/* 마우스를 따라 움직이는 홀로그램 박. 위치는 HoloPointer가 --mx, --my로 넣는다 */}
      <i className="fq-poster-holo" />
      {kicker ? <div className="fq-poster-kicker">{kicker}</div> : null}
    </div>
  );
}
