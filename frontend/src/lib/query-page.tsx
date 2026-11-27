"use client";

import { useSearchParams, type ReadonlyURLSearchParams } from "next/navigation";
import { Suspense, type ReactNode } from "react";

/**
 * 정적 내보내기(output: "export")에서는 동적 경로와 서버의 searchParams를 쓸 수 없다.
 * 화면 id와 토스 복귀 값은 브라우저에서 쿼리 문자열로 읽는다.
 * useSearchParams는 빌드 때 값이 없으므로 Suspense 안에서만 읽는다.
 */
export function QueryPage({ render }: { render: (q: ReadonlyURLSearchParams) => ReactNode }) {
  return (
    <Suspense fallback={null}>
      <Read render={render} />
    </Suspense>
  );
}

function Read({ render }: { render: (q: ReadonlyURLSearchParams) => ReactNode }) {
  return render(useSearchParams());
}

/** 쿼리 값을 숫자로 읽는다. 없으면 NaN이라 화면은 기존처럼 조회 실패로 처리한다 */
export function numberParam(q: ReadonlyURLSearchParams, name: string): number {
  const v = q.get(name);
  return v === null || v === "" ? NaN : Number(v);
}
