"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { AuctionStatusChip } from "@/components/Cards";
import Chip from "@/components/casino/Chip";
import Odometer from "@/components/casino/Odometer";
import { useToast } from "@/components/Toast";
import { ApiError, api, tokenStore } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { countdown, msLeft, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import LoadError from "@/components/LoadError";
import type { AuctionDetail, AuctionResult, AuctionSnapshot, BidResult, HighestBid, WalletView } from "@/lib/types";
import { connectSse } from "@/lib/sse";
import "./auction.css";

export default function AuctionView({ id }: { id: number }) {
  const now = useNow();
  const toast = useToast();
  const { profile } = useAuth();
  const { data: a, setData, error, loading, reload } = useLoad(() => api<AuctionDetail>(`/api/auctions/${id}`), [id]);
  const wallet = useLoad<WalletView | null>(
    () => (profile ? api<WalletView>("/api/me/wallet") : Promise.resolve(null)),
    [profile?.memberId],
  );
  const [feed, setFeed] = useState<(HighestBid & { mine?: boolean })[]>([]);
  const [amount, setAmount] = useState<number>(0);
  const [busy, setBusy] = useState(false);
  const [live, setLive] = useState<"sse" | "reconnecting" | "login" | "off">("off");
  const [result, setResult] = useState<AuctionResult | null>(null);
  const [deleted, setDeleted] = useState(false);
  const [bidRetry, setBidRetry] = useState<{ key: string; amount: number } | null>(null);
  const [serverOffset, setServerOffset] = useState(0);
  const aRef = useRef(a);
  aRef.current = a;
  const ctaRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (a?.serverTime) setServerOffset(new Date(a.serverTime).getTime() - Date.now());
  }, [a?.serverTime]);

  useEffect(() => {
    if (a?.status !== "CLOSED" || result) return;
    let cancelled = false;
    const poll = async () => {
      for (let attempt = 0; attempt < 5 && !cancelled; attempt++) {
        try { const value = await api<AuctionResult>(`/api/auctions/${id}/result`); if (!cancelled) setResult(value); return; }
        catch (e) {
          if (!(e instanceof ApiError) || e.code !== "AUCTION_RESULT_NOT_FOUND") return;
          if (attempt < 4) await new Promise((resolve) => setTimeout(resolve, 3000));
        }
      }
    };
    void poll();
    return () => { cancelled = true; };
  }, [a?.status, id, result]);

  // 최고가가 바뀌면 피드에 쌓는다
  const pushBid = (b: HighestBid | null, mine = false) => {
    if (!b) return;
    setFeed((f) => (f[0]?.amount === b.amount ? f : [{ ...b, mine }, ...f].slice(0, 8)));
  };

  useEffect(() => {
    if (a && amount < a.minimumBidAmount) setAmount(a.minimumBidAmount);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a?.minimumBidAmount]);

  useEffect(() => {
    if (a?.highestBid && feed.length === 0) pushBid(a.highestBid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a?.auctionId]);

  // 인증된 실시간 스트림. 실제 API가 끊기면 화면 상태를 다시 읽고 재연결한다.
  useEffect(() => {
    if (!a || !["OPEN", "SCHEDULED"].includes(a.status)) return;
    if (!profile) { setLive("login"); return; }
    if (!tokenStore.get()) { setLive("login"); return; }
    let stopped = false;
    let controller: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let delay = 1000;
    const isClosed = () => aRef.current?.status === "CLOSED";
    const handleClosed = async (snapshot: AuctionSnapshot) => {
      setLive("off");
      if (snapshot.result) setResult({ auctionId: id, ...snapshot.result, type: snapshot.result.type as "SOLD" | "UNSOLD", finalizedAt: new Date().toISOString() });
      else {
        for (let attempt = 0; attempt < 5 && !stopped; attempt++) {
          try { setResult(await api<AuctionResult>(`/api/auctions/${id}/result`)); return; }
          catch (e) {
            if (!(e instanceof ApiError) || e.code !== "AUCTION_RESULT_NOT_FOUND") break;
            await new Promise((r) => setTimeout(r, 3000));
          }
        }
      }
    };
    const apply = (s: AuctionSnapshot) => {
      const cur = aRef.current;
      if (!cur || s.revision <= cur.revision) return;
      setData({ ...cur, status: s.status, revision: s.revision, highestBid: s.highestBid, minimumBidAmount: s.minimumBidAmount, endsAt: s.endsAt });
      pushBid(s.highestBid, s.highestBid?.nickname === profile?.nickname);
      if (s.status === "CLOSED") void handleClosed(s);
    };
    const run = async () => {
      while (!stopped && aRef.current && aRef.current.status !== "CLOSED") {
        controller = new AbortController();
        setLive(delay === 1000 ? "sse" : "reconnecting");
        let sawMessage = false;
        const status = await connectSse(`/api/auctions/${id}/stream`, controller.signal, (message) => {
          if (message.event !== "auction") return;
          try { sawMessage = true; setLive("sse"); apply(JSON.parse(message.data) as AuctionSnapshot); delay = 1000; }
          catch { /* 잘못된 이벤트는 무시하고 다음 이벤트를 기다린다 */ }
        }).catch(() => 0);
        if (stopped || isClosed()) break;
        if (status === 404) { setDeleted(true); setLive("off"); break; }
        if (status === 401) { setLive("login"); break; }
        setLive("reconnecting");
        try { const latest = await reload(); if (latest.data.status === "CLOSED") { setData(latest.data); break; } }
        catch (e) { if (e instanceof ApiError && e.status === 404) { setDeleted(true); break; } }
        if (sawMessage) delay = 1000;
        await new Promise<void>((resolve) => { timer = setTimeout(resolve, delay); });
        delay = delay === 1000 ? 2000 : 5000;
      }
    };
    void run();
    return () => {
      stopped = true;
      controller?.abort();
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a?.auctionId, a?.status, profile?.memberId]);

  const avail = wallet.data?.availableAmount;
  const bidErr = !a
    ? null
    : amount < a.minimumBidAmount
      ? `최소 입찰가(${won(a.minimumBidAmount)}) 이상을 입력해 주세요.`
      : avail !== undefined && amount > avail
        ? `사용 가능 크레딧(${won(avail)})이 부족해요. 크레딧을 먼저 충전해 주세요.`
        : null;
  const [touched, setTouched] = useState(false);

  const grant = async () => {
    try {
      const data = await api<WalletView>("/api/me/wallet/grants", { method: "POST" });
      wallet.setData(data);
      toast("테스트 크레딧이 충전됐어요", "ok");
    } catch (e) {
      toast((e as ApiError).message, "err");
    }
  };

  const bid = async (retry?: { key: string; amount: number }) => {
    if (!a) return;
    setTouched(true);
    if (bidErr && !retry) return;
    const attempt = retry ?? { key: crypto.randomUUID(), amount };
    setBusy(true);
    try {
      const r = await api<BidResult>(`/api/auctions/${id}/bids`, { method: "POST", idempotent: attempt.key, body: { amount: attempt.amount } });
      if (r.status === "HIGHEST") {
        const hb = { nickname: profile?.nickname ?? "나", amount: r.amount, acceptedAt: r.acceptedAt };
        setData({ ...a, highestBid: hb, minimumBidAmount: r.amount + a.minimumIncrementAmount, endsAt: r.endsAt });
        pushBid(hb, true);
        burstFrom(ctaRef.current);
      } else setData(await api<AuctionDetail>(`/api/auctions/${id}`));
      setBidRetry(null);
      void wallet.reload();
      toast(r.status === "HIGHEST" ? `${won(r.amount)}에 입찰했어요. 현재 최고가예요.` : "입찰 상태를 확인했어요.", "ok");
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "BID_AMOUNT_TOO_LOW") {
        const minimum = Number(err.details?.minimumBidAmount);
        if (Number.isFinite(minimum)) { setData({ ...a, minimumBidAmount: minimum }); setAmount(minimum); }
        toast(Number.isFinite(minimum) ? `최소 입찰가는 ${won(minimum)}이에요.` : err.message, "err");
      } else if (err.code === "BID_ALREADY_HIGHEST") toast("이미 최고 입찰자예요.", "err");
      else if (err.code === "BID_BY_SELLER") toast("내가 올린 경매에는 입찰할 수 없어요.", "err");
      else if (err.code === "CREDIT_INSUFFICIENT") { void wallet.reload(); toast("사용 가능한 크레딧이 부족해요.", "err"); }
      else if (["AUCTION_NOT_OPEN", "AUCTION_CLOSED"].includes(err.code)) { void reload(); toast("경매 상태가 바뀌어 최신 정보로 다시 불러왔어요.", "err"); }
      else if (err.code === "AUCTION_NOT_FOUND") { setDeleted(true); toast("삭제된 경매예요.", "err"); }
      else if (err.code === "IDEMPOTENCY_KEY_CONFLICT") { setBidRetry(null); toast("입찰을 처리하지 못했어요. 다시 입찰해 주세요.", "err"); }
      else if (err.isUnavailable) { setBidRetry(attempt); toast("입찰 결과를 확인하지 못했어요. 아래 버튼으로 결과를 다시 확인해 주세요.", "err"); }
      else toast(err.message, "err");
    } finally {
      setBusy(false);
    }
  };

  const adjustedNow = now + serverOffset;
  const left = a ? msLeft(a.endsAt, adjustedNow) : 0;
  const urgent = a?.status === "OPEN" && left < 60_000;
  const leading = a?.highestBid && profile && a.highestBid.nickname === profile.nickname;

  if (error) return <LoadError error={error} backHref="/auctions" />;
  if (loading || !a) return <div className="fq-skel" style={{ height: 420 }} />;

  return (
    <div className="fq-detail fq-au fq-au-detail">
      <Link href="/auctions" className="fq-back">
        ← 경매 목록
      </Link>
      <div className="fq-detail-grid">
        <div className="fq-detail-art">
          <div className={`fq-au-stage is-${a.status.toLowerCase()}`}>
            <span className="fq-au-stage-kicker">{a.status === "OPEN" ? "NOW ON THE TABLE" : a.status === "SCHEDULED" ? "COMING TO THE TABLE" : "TABLE CLOSED"}</span>
            <Chip seed={a.auctionId} top={a.status === "OPEN" ? "LIVE" : a.status === "SCHEDULED" ? "SOON" : "SOLD"} big="Q" size={230} still={a.status === "CLOSED"} />
            <strong className="fq-au-stage-word">{a.itemName}</strong>
          </div>
          {a ? (
            <div className="fq-card is-pad fq-item-desc">
              <span className="fq-eyebrow">출품 정보</span>
              <p>
                <b>{a.sellerNickname}</b>, {a.itemName}
              </p>
              <p className="fq-muted">{a.itemDescription}</p>
            </div>
          ) : null}
        </div>

        <div className="fq-detail-info">
          <div className="fq-row">
            {a ? <AuctionStatusChip a={a} now={now} /> : null}
            {live === "sse" ? <span className="fq-chip is-mint">● 실시간 연결</span> : live === "reconnecting" ? <span className="fq-chip is-warn">● 재연결 중</span> : live === "login" ? <span className="fq-chip">로그인하면 실시간으로 볼 수 있어요</span> : null}
          </div>
          {deleted ? <p className="fq-err" role="alert">삭제된 경매예요.</p> : null}
          <h2 className="fq-detail-title">{a?.title ?? "…"}</h2>

          {a ? (
            <div className={`fq-board${urgent ? " is-urgent" : ""}${leading ? " is-leading" : ""}`}>
              <div className="fq-board-top">
                <span>{a.status === "CLOSED" ? "최종 낙찰가" : "현재 최고가"}</span>
                <span className="fq-num fq-board-clock">
                  {a.status === "OPEN" ? countdown(a.endsAt, adjustedNow) : a.status === "SCHEDULED" ? `시작 ${countdown(a.startsAt, adjustedNow)}` : "종료"}
                </span>
              </div>
              <Odometer value={a.highestBid?.amount ?? a.startingPriceAmount} className="fq-board-price fq-num" />
              <div className="fq-board-who">
                {a.highestBid ? (
                  <>
                    <i aria-hidden="true" />
                    {leading ? "현재 최고 입찰자예요" : `최고 입찰자 ${a.highestBid.nickname}`}
                  </>
                ) : (
                  "아직 입찰이 없어요."
                )}
              </div>
              {urgent ? <div className="fq-board-alert">마감 1분 전이에요. 지금 입찰하면 마감이 {a.extensionDurationSeconds}초 늘어나요</div> : null}
            </div>
          ) : (
            <div className="fq-skel" style={{ height: 200 }} />
          )}

          {a?.status === "OPEN" ? (
            <div className="fq-card is-pad fq-bidbox">
              <div className="fq-between">
                <span className="fq-eyebrow">입찰하기</span>
                {wallet.data ? (
                  <span className="fq-muted fq-num" style={{ fontSize: 13 }}>
                    사용 가능 크레딧 <b>{won(wallet.data.availableAmount)}</b>
                  </span>
                ) : null}
              </div>
              <div className="fq-bid-row">
                <input
                  className="fq-input fq-num"
                  type="number"
                  inputMode="numeric"
                  min={a.minimumBidAmount}
                  step={1}
                  value={amount || ""}
                  aria-label="입찰 금액"
                  aria-invalid={touched && !!bidErr}
                  onChange={(e) => {
                    setTouched(true);
                    setAmount(Number(e.target.value));
                  }}
                />
                <span className="fq-bid-unit">원</span>
              </div>
              <div className="fq-quick">
                {[1, 2, 5, 10].map((k) => (
                  <button key={k} type="button" className="fq-btn is-ghost is-sm" onClick={() => setAmount(a.minimumBidAmount + a.minimumIncrementAmount * (k - 1))}>
                    +{won(a.minimumIncrementAmount * k)}
                  </button>
                ))}
              </div>
              <p className="fq-muted" style={{ margin: 0, fontSize: 13 }}>
                최소 입찰 가능 금액 <b className="fq-num">{won(a.minimumBidAmount)}</b>
              </p>
              {touched && bidErr && !bidRetry ? (
                <p className="fq-err" role="alert">
                  {bidErr}{" "}
                  {avail !== undefined && amount > avail ? (
                    <button type="button" className="fq-link" onClick={grant}>
                      + 테스트 크레딧 충전
                    </button>
                  ) : null}
                </p>
              ) : null}
              {profile ? (
                <button ref={ctaRef} className="fq-btn is-hot is-block fq-big" disabled={busy || left <= 0 || (touched && !!bidErr && !bidRetry)} onClick={() => bid(bidRetry ?? undefined)}>
                  {busy ? "입찰 중…" : bidRetry ? `${won(bidRetry.amount)} 입찰 결과 다시 확인` : `${won(amount || a.minimumBidAmount)} 입찰`}
                </button>
              ) : (
                <Link href="/login" className="fq-btn is-block">
                  로그인하고 입찰하기
                </Link>
              )}
            </div>
          ) : null}

          {result ? <div className="fq-card is-pad"><b>{result.type === "SOLD" ? `낙찰: ${result.winnerNickname}, ${won(result.amount ?? 0)}` : "유찰"}</b></div> : null}
          <div className="fq-feed">
            <span className="fq-eyebrow">실시간 최고가 변화</span>
            <p className="fq-muted">이 화면을 연 뒤의 최고가 변화만 보여요.</p>
            <ul>
              {feed.length === 0 ? <li className="fq-muted">아직 입찰 기록이 없어요</li> : null}
              {feed.map((b, i) => (
                <li key={`${b.amount}-${b.acceptedAt}`} className={`${i === 0 ? "is-top" : ""}${b.mine ? " is-mine" : ""}`}>
                  <span>
                    {i === 0 ? "" : ""}
                    {b.nickname}
                  </span>
                  <b className="fq-num">{won(b.amount)}</b>
                  <small className="fq-num">{new Date(b.acceptedAt).toLocaleTimeString("ko-KR")}</small>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
}
