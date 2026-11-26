import { refreshAccessToken, tokenStore } from "./api";

export type SseMessage = { event: string; id?: string; data: string };

/** Fetch 기반 SSE: Authorization 헤더를 사용하고, 메시지 경계(빈 줄)에서 이벤트를 전달한다. */
export async function connectSse(
  path: string,
  signal: AbortSignal,
  onMessage: (message: SseMessage) => void,
): Promise<number> {
  const request = () => fetch(path, {
    headers: {
      Accept: "text/event-stream",
      ...(tokenStore.get() ? { Authorization: `Bearer ${tokenStore.get()}` } : {}),
    },
    credentials: "include",
    cache: "no-store",
    signal,
  });
  let response = await request();
  if (response.status === 401 && await refreshAccessToken()) response = await request();
  if (!response.ok) return response.status;
  if (!response.body) return 0;

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let event = "message";
  let id: string | undefined;
  let data: string[] = [];
  const dispatch = () => {
    if (data.length) onMessage({ event, id, data: data.join("\n") });
    event = "message";
    data = [];
  };
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (line === "") { dispatch(); continue; }
        if (line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") event = value;
        else if (field === "id" && !value.includes("\0")) id = value;
        else if (field === "data") data.push(value);
      }
    }
  } finally {
    reader.releaseLock();
  }
  return response.status;
}
