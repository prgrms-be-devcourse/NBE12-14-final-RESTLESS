"use client";

import { setIntro } from "@/lib/intro";
import { scrollToEl, scrollToY } from "@/lib/smooth";
import "./chapter.css";

/** 연출을 끄면 무대가 빠지며 높이가 바뀌므로, 다시 그린 뒤 건너뛸 곳으로 바로 옮긴다 */
function skipForGood(skipTo: string) {
  setIntro(false);
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      const el = document.querySelector(skipTo);
      scrollToY(el ? el.getBoundingClientRect().top + window.scrollY - 16 : 0, { immediate: true });
    }),
  );
}

/**
 * 긴 스크롤 연출의 위치 표시와 건너뛰기 버튼. 무대(position: relative) 안에 둔다.
 * 부모가 CSS 변수 --p(0~1)를 넣어 주면 진행 막대가 따라간다.
 * skipTo가 있으면 다음 방문부터 연출을 끄는 단추도 함께 둔다.
 */
export default function ChapterHud({ names, index, skipTo, onSkip, skipLabel = "건너뛰기 ↓", tone = "dark" }: { names: string[]; index: number; skipTo?: string; onSkip?: () => void; skipLabel?: string; tone?: "dark" | "light" }) {
  return (
    <>
      <div className={`fq-hud is-${tone}`} aria-hidden="true">
        <span className="fq-hud-no fq-num">
          {String(index + 1).padStart(2, "0")}
          <small> / {String(names.length).padStart(2, "0")}</small>
        </span>
        <span key={index} className="fq-hud-name">
          {names[index]}
        </span>
        <span className="fq-hud-bar">
          <i />
        </span>
      </div>
      {skipTo || onSkip ? (
        <div className="fq-skips">
          <button type="button" className={`fq-skip is-${tone}`} onClick={() => (onSkip ? onSkip() : scrollToEl(document.querySelector(skipTo!)))}>
            {skipLabel}
          </button>
          {skipTo ? (
            <button type="button" className={`fq-skip is-quiet is-${tone}`} onClick={() => skipForGood(skipTo)} title="메뉴의 연출 단추로 다시 켤 수 있어요">
              다음부터 연출 없이 보기
            </button>
          ) : null}
        </div>
      ) : null}
    </>
  );
}
