"use client";

import LineArt from "@/components/LineArt";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useToast } from "@/components/Toast";
import { ApiError, api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { burstFrom } from "@/lib/confetti";
import { countdown, dateOnly, won } from "@/lib/format";
import { useLoad, useNow } from "@/lib/hooks";
import { ORDER_STATUS, checkoutHref, orderTitle } from "@/lib/orders";
import type { OrderPage, Profile, TicketReservationDetail, WalletView } from "@/lib/types";

export default function MePage() {
  const { profile, ready, setProfile } = useAuth();
  if (!ready) return <div className="fq-skel" style={{ height: 300 }} />;
  if (!profile)
    return (
      <div className="fq-card is-pad fq-empty">
        <LineArt kind="ticket" />
        <h2>로그인이 필요해요</h2>
        <p className="fq-muted">로그인하면 주문 내역과 크레딧을 확인할 수 있어요.</p>
        <div className="fq-row" style={{ justifyContent: "center" }}>
          <Link className="fq-btn is-grad" href="/login">
            로그인
          </Link>
          <Link className="fq-btn is-ghost" href="/signup">
            회원가입
          </Link>
        </div>
      </div>
    );
  return <MeInner profile={profile} setProfile={setProfile} />;
}

function MeInner({ profile, setProfile }: { profile: Profile; setProfile: (p: Profile) => void }) {
  const toast = useToast();
  const now = useNow();
  const wallet = useLoad(() => api<WalletView>("/api/me/wallet"));
  const tickets = useLoad(() => api<TicketReservationDetail[]>("/api/ticket-reservations/active"));
  const orders = useLoad(() => api<OrderPage>("/api/me/orders?size=20").then((page) => page.items));
  const [form, setForm] = useState({
    nickname: profile.nickname,
    defaultRecipient: profile.defaultRecipient ?? "",
    defaultPhone: profile.defaultPhone ?? "",
    defaultAddress: profile.defaultAddress ?? "",
  });
  const [saving, setSaving] = useState(false);
  const grantRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    setForm({
      nickname: profile.nickname,
      defaultRecipient: profile.defaultRecipient ?? "",
      defaultPhone: profile.defaultPhone ?? "",
      defaultAddress: profile.defaultAddress ?? "",
    });
  }, [profile]);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const r = await api<Profile>("/api/me/profile", { method: "PATCH", body: form });
      setProfile(r);
      toast("프로필을 저장했어요", "ok");
    } catch (err) {
      const ex = err as ApiError;
      const fields = ex.details?.fields as Record<string, string> | undefined;
      toast(fields ? Object.values(fields)[0] : ex.message, "err");
    } finally {
      setSaving(false);
    }
  };

  const grant = async () => {
    try {
      const r = await api<WalletView>("/api/me/wallet/grants", { method: "POST" });
      wallet.setData(r);
      burstFrom(grantRef.current);
      toast("테스트 크레딧이 충전됐어요", "ok");
    } catch (err) {
      toast((err as ApiError).message, "err");
    }
  };

  const w = wallet.data;
  const usedPct = w && w.balanceAmount ? (w.reservedAmount / w.balanceAmount) * 100 : 0;

  return (
    <div className="fq-me">
      <section className="fq-me-hero">
        <i className="fq-me-avatar" aria-hidden="true">
          {profile.nickname.slice(0, 1).toUpperCase()}
        </i>
        <div>
          <span className="fq-chip">{profile.role === "ADMIN" ? "관리자" : profile.role === "ORGANIZER" ? "주최자" : "FAN"}</span>
          <h2>{profile.nickname}</h2>
          <p className="fq-muted">{profile.email}</p>
        </div>
      </section>

      <div className="fq-me-grid">
        <div className="fq-me-main">
          <section className="fq-card is-pad">
            <div className="fq-between">
              <span className="fq-eyebrow">주문과 참여 내역</span>
              <Link href="/me/orders" className="fq-link">
                전체 보기 →
              </Link>
            </div>
            <ul className="fq-list">
              {orders.data && orders.data.length === 0 ? <li className="fq-muted">아직 주문이 없어요</li> : null}
              {(orders.data ?? []).slice(0, 3).map((o) => (
                <li key={o.orderId} className="fq-list-link">
                  {/* 줄 전체를 누르면 주문 상세(결제, 환불 화면)로 간다 */}
                  <Link href={checkoutHref({ orderId: o.orderId, kind: o.orderType, title: orderTitle(o), amount: o.totalAmount, back: "/me" })}>
                    <span className="fq-dot" aria-hidden="true" />
                    <div>
                      <b>{orderTitle(o)}</b>
                      <small className="fq-muted">#{o.orderId}, {won(o.totalAmount)}</small>
                    </div>
                    <span className={`fq-chip ${ORDER_STATUS[o.status]?.tone ?? ""}`}>{ORDER_STATUS[o.status]?.label ?? o.status}</span>
                    <i className="fq-list-go" aria-hidden="true">→</i>
                  </Link>
                </li>
              ))}
            </ul>
          </section>

          <section className="fq-card is-pad">
            <div className="fq-between">
              <span className="fq-eyebrow">진행 중인 예매</span>
              <Link href="/me/tickets" className="fq-link">
                내 티켓 →
              </Link>
            </div>
            <ul className="fq-list">
              {(tickets.data ?? []).length === 0 ? <li className="fq-muted">진행 중인 예매가 없어요</li> : null}
              {(tickets.data ?? []).map((t) => (
                <li key={t.reservationId}>
                  <span className="fq-dot" aria-hidden="true" />
                  <div>
                    <b>예약 #{t.reservationId}</b>
                    <small className="fq-muted">좌석 {t.seats.length}석{t.holdExpired ? ", 선점 만료 처리 대기" : ""}</small>
                  </div>
                  <span className="fq-chip is-live fq-num">{t.paymentExpiresAt ? countdown(t.paymentExpiresAt, now) : countdown(t.holdExpiresAt, now)}</span>
                </li>
              ))}
            </ul>
          </section>

          <section className="fq-card is-pad fq-me-form">
            <span className="fq-eyebrow">프로필과 기본 배송지</span>
            <form className="fq-form" onSubmit={save}>
              <div className="fq-field">
                <label htmlFor="nick">닉네임</label>
                <input id="nick" className="fq-input" value={form.nickname} minLength={2} maxLength={30} onChange={(e) => setForm({ ...form, nickname: e.target.value })} />
              </div>
              <div className="fq-form-2">
                <div className="fq-field">
                  <label htmlFor="rcp">받는 분</label>
                  <input id="rcp" className="fq-input" value={form.defaultRecipient} maxLength={50} onChange={(e) => setForm({ ...form, defaultRecipient: e.target.value })} />
                </div>
                <div className="fq-field">
                  <label htmlFor="ph">연락처</label>
                  <input id="ph" className="fq-input" value={form.defaultPhone} maxLength={20} pattern="[0-9\-]*" onChange={(e) => setForm({ ...form, defaultPhone: e.target.value })} />
                </div>
              </div>
              <div className="fq-field">
                <label htmlFor="addr">주소</label>
                <input id="addr" className="fq-input" value={form.defaultAddress} maxLength={255} onChange={(e) => setForm({ ...form, defaultAddress: e.target.value })} />
              </div>
              <button className="fq-btn is-grad fq-me-save" disabled={saving}>
                {saving ? "저장 중…" : "저장하기"}
              </button>
            </form>
          </section>
        </div>
        <aside className="fq-me-side">
          <section className="fq-card is-pad fq-wallet">
            <div className="fq-between">
              <span className="fq-eyebrow">경매 크레딧</span>
              <button ref={grantRef} className="fq-btn is-sm is-ghost" onClick={grant}>
                + 테스트 충전
              </button>
            </div>
            <b className="fq-wallet-amt fq-num">{w ? won(w.availableAmount) : "…"}</b>
            <div className="fq-wallet-bar" aria-hidden="true">
              <i style={{ width: `${100 - usedPct}%` }} />
            </div>
            <dl className="fq-kv">
              <dt>전체 잔액</dt>
              <dd className="fq-num">{w ? won(w.balanceAmount) : "…"}</dd>
              <dt>입찰에 묶인 금액</dt>
              <dd className="fq-num">{w ? won(w.reservedAmount) : "…"}</dd>
            </dl>
          </section>
          <Withdraw />
        </aside>
      </div>
    </div>
  );
}

/** 회원 탈퇴: 비밀번호 재확인 + 필수 동의. 결제 대기, 확인 중 거래가 있으면 409로 막힌다 */
function Withdraw() {
  const toast = useToast();
  const router = useRouter();
  const { logout } = useAuth();
  const [pw, setPw] = useState("");
  const [agree, setAgree] = useState(false);
  const [err, setErr] = useState<{ pw?: string; agree?: string; server?: string }>({});
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof err = {};
    if (!pw) next.pw = "비밀번호를 입력해 주세요.";
    if (!agree) next.agree = "‘결제 완료 주문을 더 이상 조회하거나 환불 요청할 수 없다’는 항목에 동의해 주세요.";
    setErr(next);
    if (next.pw || next.agree) return;
    if (!confirm("정말 탈퇴할까요? 되돌릴 수 없어요.")) return;
    setBusy(true);
    try {
      await api("/api/me/withdraw", { method: "POST", body: { password: pw, paidOrderNoticeAgreed: true } });
      await logout();
      toast("탈퇴가 완료됐어요", "ok");
      router.push("/home");
    } catch (ex) {
      const x = ex as ApiError;
      if (x.code === "MEMBER_HAS_ACTIVE_PARTICIPATION" || x.status === 409)
        setErr({ server: "진행 중인 주문(결제 대기, 확인 중)이나 아직 끝나지 않은 팬미팅 참여가 있어 탈퇴할 수 없어요." });
      else if (x.status === 401 || x.status === 400 || x.code === "INVALID_PASSWORD") setErr({ pw: "비밀번호가 맞지 않아요." });
      else setErr({ server: x.message });
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className="fq-card is-pad fq-me-form fq-danger-zone">
      <summary>
        <span className="fq-eyebrow">회원 탈퇴</span>
        <small className="fq-muted">결제 대기나 확인 중인 거래가 없을 때만 할 수 있어요</small>
      </summary>
      <form className="fq-form" onSubmit={submit} noValidate>
        <div className="fq-field">
          <label htmlFor="wd-pw">비밀번호 재입력</label>
          <input
            id="wd-pw"
            className="fq-input"
            type="password"
            autoComplete="current-password"
            placeholder="현재 비밀번호"
            value={pw}
            aria-invalid={!!err.pw}
            aria-describedby={err.pw ? "wd-pw-err" : undefined}
            onChange={(e) => setPw(e.target.value)}
          />
          {err.pw ? (
            <p id="wd-pw-err" className="fq-err">
              {err.pw}
            </p>
          ) : null}
        </div>
        <label className="fq-agree">
          <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
          <span>
            <b>[필수]</b> 탈퇴하면 결제 완료 주문을 더 이상 조회하거나 환불 요청할 수 없다는 데 동의해요.
          </span>
        </label>
        {err.agree ? <p className="fq-err">{err.agree}</p> : null}
        {err.server ? (
          <p className="fq-err" role="alert">
            {err.server}
          </p>
        ) : null}
        <button className="fq-btn is-danger" disabled={busy}>
          {busy ? "처리 중…" : "회원 탈퇴하기"}
        </button>
      </form>
    </details>
  );
}
