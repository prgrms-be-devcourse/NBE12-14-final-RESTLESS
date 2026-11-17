-- 시드 보조 테이블을 지우고 옵티마이저 통계를 갱신한다. 대량 INSERT 직후 통계가 낡으면 실험 첫 회의 쿼리 계획이 달라진다.
DROP TABLE IF EXISTS _perf_seq, _perf_venues, _perf_artists, _perf_products, _perf_extra_bids;

ANALYZE TABLE users, refresh_tokens, venues, concerts,
    drops, drop_variants, drop_inventories, drop_reservations, drop_purchase_slots, drop_queue_entries,
    orders, order_items, order_shipping_snapshots, payments, payment_refunds,
    auctions, auction_bids, auction_results, credit_wallets, credit_holds, credit_transactions;
