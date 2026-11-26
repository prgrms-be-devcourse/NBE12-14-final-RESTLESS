"use client";

import type { ApiErrorBody, LoginResponse } from "./types";

/**
 * REST 통신 레이어.
 * - Access Token: localStorage에 두고 `Authorization: Bearer` 로 보낸다.
 * - Refresh Token: HttpOnly 쿠키(Path=/api/auth)라 credentials: 'include' 로만 오간다.
 * - 401이면 /api/auth/refresh 를 한 번 시도한 뒤 원래 요청을 다시 보낸다.
 */

const TOKEN_KEY = "fanq.accessToken";

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* 저장 불가 환경은 무시 */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* 무시 */
    }
  },
};

export class ApiError extends Error {
  status: number;
  code: string;
  details: Record<string, unknown> | null;
  /** 429, 503 응답의 Retry-After(초 또는 HTTP 날짜)를 밀리초로 바꾼 값. 없으면 null */
  retryAfterMs: number | null;
  constructor(status: number, body: Partial<ApiErrorBody>, retryAfter?: string | null) {
    super(body.message ?? "요청을 처리하지 못했어요.");
    this.status = status;
    this.code = body.code ?? "UNKNOWN";
    this.details = body.details ?? null;
    this.retryAfterMs = parseRetryAfter(retryAfter);
  }
  /** 백엔드에 닿지 못했거나 서버 오류인지 판별한다. */
  get isUnavailable() {
    return this.status === 0 || this.status >= 500;
  }
}

function parseRetryAfter(v: string | null | undefined): number | null {
  if (!v) return null;
  const sec = Number(v.trim());
  if (Number.isFinite(sec)) return sec >= 0 ? sec * 1000 : null;
  const at = Date.parse(v);
  return Number.isNaN(at) ? null : Math.max(0, at - Date.now());
}

type Options = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** 경쟁 요청(좌석 선점, 재고 예약, 입찰, 결제 준비)은 Idempotency-Key 헤더가 필수 */
  idempotent?: boolean | string;
};

let refreshing: Promise<boolean> | null = null;

export async function refreshAccessToken(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const res = await fetch("/api/auth/refresh", { method: "POST", credentials: "include" });
      if (!res.ok) return false;
      const data = (await res.json()) as LoginResponse;
      tokenStore.set(data.accessToken);
      return true;
    } catch {
      return false;
    } finally {
      setTimeout(() => (refreshing = null), 0);
    }
  })();
  return refreshing;
}

export async function api<T>(path: string, opts: Options = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.idempotent) {
    headers["Idempotency-Key"] =
      typeof opts.idempotent === "string" ? opts.idempotent : crypto.randomUUID();
  }

  let res: Response;
  try {
    res = await fetch(path, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      credentials: "include",
    });
  } catch {
    throw new ApiError(0, { code: "NETWORK", message: "서버에 연결할 수 없어요." });
  }

  if (res.status === 401 && !retried && !path.startsWith("/api/auth/")) {
    if (await refreshAccessToken()) return api<T>(path, opts, true);
    tokenStore.clear();
  }

  if (res.status === 204) return undefined as T;

  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // 프록시가 백엔드에 닿지 못하면 HTML/텍스트 500이 온다
    if (!res.ok) throw new ApiError(res.status || 0, { code: "NETWORK", message: "서버에 연결할 수 없어요." }, res.headers.get("Retry-After"));
  }
  if (!res.ok) throw new ApiError(res.status, (json ?? {}) as ApiErrorBody, res.headers.get("Retry-After"));
  return json as T;
}
