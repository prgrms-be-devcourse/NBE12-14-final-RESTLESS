// 드롭 구매 흐름의 저장, 복구 규칙 검사. 실행: node tests/drop-purchase-check.cjs
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const src = fs.readFileSync(path.join(__dirname, '../src/app/(main)/drops/detail/purchase.ts'), 'utf8');
const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const mod = { exports: {} };
vm.runInNewContext(js, { module: mod, exports: mod.exports, JSON, Math, Number, Error, String });
const p = mod.exports;
const load = (rel) => {
  const out = ts.transpileModule(fs.readFileSync(path.join(__dirname, rel), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const m = { exports: {} };
  vm.runInNewContext(out, { module: m, exports: m.exports, URLSearchParams });
  return m.exports;
};
const orders = load('../src/lib/orders.ts');
const plain = (x) => JSON.parse(JSON.stringify(x));

function memStore() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), _m: m };
}

const addr = { recipient: '홍길동', phone: '010-1234-5678', address: '서울시 중구 1' };

// 배송지 검증
assert.equal(p.addressProblem(addr), null);
assert.ok(p.addressProblem({ ...addr, recipient: '  ' }));
assert.ok(p.addressProblem({ ...addr, phone: 'abc' }));
assert.ok(p.addressProblem({ ...addr, address: 'x'.repeat(256) }));
assert.deepEqual(plain(p.trimAddress({ recipient: ' a ', phone: ' 010-1111-2222 ', address: ' b ' })), { recipient: 'a', phone: '010-1111-2222', address: 'b' });

// 기본 배송지가 없으면 빈 값
assert.deepEqual(plain(p.defaultAddress({ defaultRecipient: null, defaultPhone: null, defaultAddress: null })), { recipient: '', phone: '', address: '' });

// 저장과 복구: 같은 회원, 드롭, SKU만 쓴다
const store = memStore();
const pending = { key: 'k-1', body: { variantId: 10, ...addr } };
p.savePurchase({ v: 1, memberId: 1, dropId: 5, variantId: 10, address: addr, pending, done: null }, store);
const saved = p.loadPurchase(1, 5, store);
assert.equal(saved.pending.key, 'k-1');
assert.deepEqual(plain(saved.pending.body), pending.body);
assert.ok(p.restoreFor(saved, 1, 5, 10));
assert.equal(p.restoreFor(saved, 1, 5, 11), null, '다른 SKU의 배송지는 쓰지 않는다');
assert.equal(p.restoreFor(saved, 1, 5, undefined), null);
assert.equal(p.loadPurchase(2, 5, store), null, '다른 회원의 저장본은 읽히지 않는다');
assert.equal(p.restoreFor(saved, 2, 5, 10), null, '다른 회원이면 쓰지 않는다');
assert.equal(p.loadPurchase(1, 6, store), null);

// 키를 바꿔 넣은 저장본도 내용의 회원이 다르면 버린다
store.setItem('fanq.drop.purchase.3.5', store.getItem('fanq.drop.purchase.1.5'));
assert.equal(p.loadPurchase(3, 5, store), null);

// pending body의 SKU가 저장본과 다르면 pending만 버린다
p.savePurchase({ v: 1, memberId: 1, dropId: 5, variantId: 10, address: addr, pending: { key: 'k-2', body: { variantId: 99, ...addr } } }, store);
assert.equal(p.loadPurchase(1, 5, store).pending, null);

// 깨진 값은 무시
store.setItem('fanq.drop.purchase.1.7', '{oops');
assert.equal(p.loadPurchase(1, 7, store), null);

// done 저장본: 결제 준비 키가 남고, SKU가 다르거나 모양이 깨지면 버린다
const done = { reservationId: 3, orderId: 7, amount: 39000, expiresAt: '2026-11-25T10:00:00Z', variantId: 10, prepareKey: 'pk-1' };
p.savePurchase({ v: 1, memberId: 1, dropId: 5, variantId: 10, address: addr, pending: null, done }, store);
assert.equal(p.loadPurchase(1, 5, store).done.prepareKey, 'pk-1', '새로고침 뒤에도 같은 결제 준비 키를 쓴다');
p.savePurchase({ v: 1, memberId: 1, dropId: 5, variantId: 10, address: addr, pending: null, done: { ...done, variantId: 11 } }, store);
assert.equal(p.loadPurchase(1, 5, store).done, null);
p.savePurchase({ v: 1, memberId: 1, dropId: 5, variantId: 10, address: addr, pending: null, done: { ...done, reservationId: '3' } }, store);
assert.equal(p.loadPurchase(1, 5, store).done, null);

p.clearPurchase(1, 5, store);
assert.equal(p.loadPurchase(1, 5, store), null);

// 대기 조회 간격: 서버 pollAfterMs보다 자주 묻지 않는다. 없으면 계약의 순번 구간표를 쓴다
assert.equal(p.pollDelay({ position: 1500, pollAfterMs: 5000 }), 5000);
assert.equal(p.pollDelay({ position: 5, pollAfterMs: 1000 }), 1000);
assert.equal(p.pollDelay({ position: 5, pollAfterMs: 7000 }), 7000, '서버가 늘리면 따른다');
assert.equal(p.pollDelay({ position: 5, pollAfterMs: 100 }), 1000, '1초 5회 제한 아래로 내려가지 않는다');
assert.equal(p.pollDelay({ position: 20 }), 1000);
assert.equal(p.pollDelay({ position: 21 }), 2000);
assert.equal(p.pollDelay({ position: 200 }), 2000);
assert.equal(p.pollDelay({ position: 1000 }), 3000);
assert.equal(p.pollDelay({ position: 1001 }), 5000);
assert.equal(p.pollDelay({ position: 50, pollAfterMs: null }), 2000);
assert.equal(p.pollDelay(null), 5000, '순번을 모르면 가장 느린 간격');

// Retry-After + 지터
assert.equal(p.retryDelay(5000, 1000, () => 0), 5000);
assert.equal(p.retryDelay(5000, 1000, () => 1), 6000, '지터는 최대 20%');
assert.equal(p.retryDelay(1000, 3000, () => 0.5), 1100);
assert.equal(p.retryDelay(null, 3000, () => 0), 3000, 'Retry-After가 없으면 기본값');
assert.equal(p.retryDelay(0, 3000, () => 1), 200, '0초여도 최소 지터를 둔다');
assert.ok(p.ENTRY_BUSY_RETRIES * 5000 >= 10000, '진입 자동 재시도가 10초 5회 제한에 걸리지 않는다');
assert.ok(p.ENTRY_BUSY_RETRIES + 1 <= 5);

// 진입 실패 분류
assert.equal(p.entryFailure({ status: 503, code: 'DROP_QUEUE_BUSY' }), 'busy');
assert.equal(p.entryFailure({ status: 409, code: 'DROP_SOLD_OUT' }), 'soldOut');
assert.equal(p.entryFailure({ status: 409, code: 'DROP_NOT_ON_SALE' }), 'notOnSale');
assert.equal(p.entryFailure({ status: 429, code: 'TOO_MANY_REQUESTS' }), 'rateLimited');
assert.equal(p.entryFailure({ status: 502, code: 'NETWORK' }), 'unknown', 'DROP_QUEUE_BUSY가 아닌 5xx는 결과를 모른다');
assert.equal(p.entryFailure({ status: 0, code: 'NETWORK' }), 'unknown');
assert.equal(p.entryFailure({ status: 404, code: 'NOT_FOUND' }), 'rejected');

// 재고 점유 실패 분류: 결과를 모르면 같은 키로만 다시 확인한다
assert.equal(p.reserveFailure({ status: 0, code: 'NETWORK' }), 'unknown');
assert.equal(p.reserveFailure({ status: 500, code: 'INTERNAL' }), 'unknown');
assert.equal(p.reserveFailure({ status: 429, code: 'TOO_MANY_REQUESTS' }), 'unknown');
assert.equal(p.reserveFailure({ status: 409, code: 'DROP_SOLD_OUT' }), 'soldOut');
assert.equal(p.reserveFailure({ status: 403, code: 'DROP_ADMISSION_REQUIRED' }), 'admissionExpired');
assert.equal(p.reserveFailure({ status: 409, code: 'DROP_ACTIVE_PURCHASE_EXISTS' }), 'activeExists');
assert.equal(p.reserveFailure({ status: 400, code: 'INVALID_REQUEST' }), 'invalid');

// 대기 조회 결과: 고정한 SKU와 다른 대기는 이어가지 않는다
assert.equal(p.queueAction({ status: 'WAITING', variantId: 10 }, 10), 'wait');
assert.equal(p.queueAction({ status: 'ADMITTED', variantId: 10 }, 10), 'reserve');
assert.equal(p.queueAction({ status: 'ADMITTED', variantId: 11 }, 10), 'otherSku');
assert.equal(p.queueAction({ status: 'UNAVAILABLE', variantId: 10 }, 10), 'unavailable');
assert.equal(p.queueAction({ status: 'EXPIRED', variantId: 10 }, 10), 'expired');
assert.equal(p.queueAction({ status: 'CONSUMED', variantId: 10 }, 10), 'consumed');
assert.equal(p.queueAction({ status: 'ADMITTED' }, 10), 'reserve', 'variantId가 없으면 상태만 본다');

// 재고 점유(TX1) 결과를 모르면 같은 키로 1초, 2초 뒤 두 번 자동 재확인. 429는 Retry-After를 따른다
assert.equal(p.RESERVE_AUTO_RETRIES, 2);
assert.equal(p.reserveRetryDelay(0, null, () => 0), 1000);
assert.equal(p.reserveRetryDelay(1, null, () => 0), 2000);
assert.equal(p.reserveRetryDelay(0, 3000, () => 0), 3000);

// 결제 기한 경과: PAYMENT_WINDOW_EXPIRED와 reason이 DROP_NOT_PAYABLE인 PAYMENT_NOT_PAYABLE을 같게 본다
assert.equal(orders.isPaymentDeadlineError({ code: 'PAYMENT_WINDOW_EXPIRED', details: null }), true);
assert.equal(orders.isPaymentDeadlineError({ code: 'PAYMENT_NOT_PAYABLE', details: { reason: 'DROP_NOT_PAYABLE' } }), true);
assert.equal(orders.isPaymentDeadlineError({ code: 'PAYMENT_NOT_PAYABLE', details: { reason: 'FREE_ORDER' } }), false);
assert.equal(orders.isPaymentDeadlineError({ code: 'PAYMENT_NOT_PAYABLE', details: null }), false);
assert.equal(orders.isPaymentDeadlineError({ code: 'PAYMENT_FAILED' }), false);
assert.equal(orders.isPaymentDeadlineError(null), false);
assert.match(orders.checkoutHref({ orderId: 7, kind: 'DROP', title: 't', amount: 1, back: '/drops/1', expired: true }), /expired=1/);
assert.doesNotMatch(orders.checkoutHref({ orderId: 7, kind: 'DROP', title: 't', amount: 1 }), /expired/);

// 409 뒤 기존 주문 확인: 슬롯을 잡는 세 상태의 드롭 주문만 최신순 그대로 고른다
const items = [
  { orderId: 9, orderType: 'DROP', status: 'PAYMENT_REVIEW' },
  { orderId: 8, orderType: 'TICKET', status: 'PENDING_PAYMENT' },
  { orderId: 7, orderType: 'DROP', status: 'PAID' },
  { orderId: 6, orderType: 'DROP', status: 'PENDING_PAYMENT' },
  { orderId: 5, orderType: 'DROP', status: 'PAYMENT_CONFIRMING' },
  { orderId: 4, orderType: 'DROP', status: 'EXPIRED' },
];
assert.deepEqual(p.activeDropOrderCandidates(items).map((o) => o.orderId), [9, 6, 5]);
assert.deepEqual(plain(p.activeDropOrderCandidates(null)), []);
assert.equal(p.activeOrderAction('PENDING_PAYMENT'), 'pay');
assert.equal(p.activeOrderAction('PAYMENT_CONFIRMING'), 'checking');
assert.equal(p.activeOrderAction('PAYMENT_REVIEW'), 'checking');

// 409 뒤 기존 주문 찾기: 마지막 쪽까지 넘기고, 모르면 던진다
async function activeOrderSearch() {
  const filler = (from, n) => Array.from({ length: n }, (_, i) => ({ orderId: from - i, orderType: 'DROP', status: 'PAID' }));
  const pages = [
    { items: [{ orderId: 900, orderType: 'DROP', status: 'PENDING_PAYMENT' }, ...filler(899, 99)], totalPages: 3 },
    { items: [...filler(700, 50), { orderId: 600, orderType: 'DROP', status: 'PAYMENT_REVIEW' }, ...filler(599, 49)], totalPages: 3 },
    { items: filler(400, 10), totalPages: 3 },
  ];
  const details = { 900: { status: 'PENDING_PAYMENT', payable: { payableType: 'DROP', payableId: 9900 }, shipping: null }, 600: { status: 'PAYMENT_REVIEW', payable: { payableType: 'DROP', payableId: 6600 }, shipping: addr } };
  const calls = [];
  const io = (over = {}) => ({
    listPage: async (n) => { calls.push(`list${n}`); if (over.list?.(n)) throw over.list(n); return pages[n]; },
    detail: async (id) => { calls.push(`detail${id}`); if (over.detail?.(id)) throw over.detail(id); return details[id]; },
    reservation: async (rid) => {
      calls.push(`res${rid}`);
      if (over.res?.(rid)) throw over.res(rid);
      if (rid === 9900) throw { status: 404 }; // 다른 드롭의 주문
      return { reservationId: rid, orderId: 600, variantId: 10, amount: 39000, expiresAt: 'x' };
    },
  });
  const found = await p.findActiveDropOrder(io());
  assert.equal(found.status, 'PAYMENT_REVIEW', '두 번째 쪽의 진행 중 주문을 찾는다');
  assert.equal(found.reservation.reservationId, 6600);
  assert.deepEqual(calls, ['list0', 'detail900', 'res9900', 'list1', 'detail600', 'res6600'], '찾으면 다음 쪽을 부르지 않는다');

  calls.length = 0;
  const none = await p.findActiveDropOrder({ ...io(), detail: async (id) => ({ ...details[id], status: 'PAID' }) });
  assert.equal(none, null, '끝까지 없으면 null');
  assert.deepEqual(calls.filter((c) => c.startsWith('list')), ['list0', 'list1', 'list2'], '마지막 쪽까지 본다');

  const boom = Object.assign(new Error('x'), { status: 503 });
  for (const over of [{ list: (n) => (n === 1 ? boom : null) }, { detail: () => boom }, { res: (rid) => (rid === 6600 ? { status: 500 } : null) }, { list: () => ({ status: 0 }) }]) {
    await assert.rejects(p.findActiveDropOrder(io(over)), (e) => e instanceof p.ActiveOrderUnknown, '목록, 상세, 점유 조회가 실패하면 모른다');
  }
  await assert.rejects(p.findActiveDropOrder({ ...io(), listPage: async () => ({ items: filler(1, 1), totalPages: 999 }) }), (e) => e instanceof p.ActiveOrderUnknown, '쪽 상한을 넘으면 모른다');
  assert.equal(await p.findActiveDropOrder({ ...io(), listPage: async () => ({ items: [], totalPages: 0 }) }), null);
}

(async () => {
await activeOrderSearch();
console.log('PASS: drop purchase address validation, member and SKU scoped restore, pending retry body, poll interval, retry delay, failure classification, TX1 auto retry, payment deadline errors, active order after 409, paged active order search');
})().catch((e) => { console.error(e); process.exit(1); });
