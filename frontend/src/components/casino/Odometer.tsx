"use client";

import { useEffect, useState } from "react";
import "./casino.css";

const DIGITS = "0123456789";

/**
 * 숫자가 자리마다 굴러서 바뀌는 금액 표시. 오른쪽 자리부터 키를 매겨, 금액이 바뀌면 바뀐 자리만 돈다.
 * 읽는 값은 aria-label로 한 번에 알려 준다.
 */
export default function Odometer({ value, suffix = "원", className }: { value: number; suffix?: string; className?: string }) {
  // 처음 그릴 때는 0에서 굴러 올라오게 한 박자 늦게 실제 값을 넣는다
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const t = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(t);
  }, []);
  const text = value.toLocaleString("ko-KR");
  const chars = text.split("");
  return (
    <span className={`fq-odo${className ? " " + className : ""}`} aria-label={`${text}${suffix}`} role="text">
      {chars.map((ch, i) => {
        const pos = chars.length - i;
        if (!DIGITS.includes(ch))
          return (
            <span key={`s${pos}`} className="fq-odo-sep" aria-hidden="true">
              {ch}
            </span>
          );
        const d = ready ? Number(ch) : 0;
        return (
          <span key={`d${pos}`} className="fq-odo-col" aria-hidden="true">
            <span className="fq-odo-strip" style={{ transform: `translateY(${-d * 10}%)`, transitionDelay: `${pos * 45}ms` }}>
              {DIGITS.split("").map((n) => (
                <span key={n}>{n}</span>
              ))}
            </span>
          </span>
        );
      })}
      <span className="fq-odo-unit" aria-hidden="true">
        {suffix}
      </span>
    </span>
  );
}
