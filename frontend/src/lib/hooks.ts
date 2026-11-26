"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ApiError } from "./api";

/** API 조회 상태. 실패해도 로딩을 끝내고 호출자가 오류를 표시할 수 있다. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: ApiError | null }>({ data: null, loading: true, error: null });
  const loadRef = useRef(load);
  loadRef.current = load;
  const reload = useCallback(async () => {
    setState((s) => ({ ...s, loading: true, error: null }));
    try {
      const data = await loadRef.current();
      const next = { data, loading: false, error: null } as const;
      setState(next);
      return next;
    } catch (reason) {
      const error = reason instanceof ApiError ? reason : new ApiError(0, { code: "NETWORK", message: "서버에 연결할 수 없어요." });
      setState((s) => ({ ...s, loading: false, error }));
      throw error;
    }
  }, []);
  useEffect(() => {
    let alive = true;
    loadRef.current().then(
      (data) => { if (alive) setState({ data, loading: false, error: null }); },
      (reason) => {
        if (!alive) return;
        const error = reason instanceof ApiError ? reason : new ApiError(0, { code: "NETWORK", message: "서버에 연결할 수 없어요." });
        setState({ data: null, loading: false, error });
      },
    );
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { ...state, setData: (data: T) => setState((s) => ({ ...s, data, error: null })), reload };
}

/** 1초마다 갱신되는 현재 시각 */
export function useNow(interval = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), interval);
    return () => clearInterval(t);
  }, [interval]);
  return now;
}

export function useReducedMotion() {
  return useSyncExternalStore(
    (cb) => {
      const m = window.matchMedia("(prefers-reduced-motion: reduce)");
      m.addEventListener("change", cb);
      return () => m.removeEventListener("change", cb);
    },
    () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    () => false,
  );
}

/** 현재 테마(data-theme). 기본은 우주(dark) */
export function useTheme(): "light" | "dark" {
  return useSyncExternalStore(
    (cb) => {
      const mo = new MutationObserver(cb);
      mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
      return () => mo.disconnect();
    },
    () => (document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark"),
    () => "dark",
  );
}
