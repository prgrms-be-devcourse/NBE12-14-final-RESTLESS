-- Auction·Credit 불변식. 쿼리마다 불변식을 어긴 행의 ID를 돌려준다. 0행이면 지켜진 것이다.
-- 경매 테스트가 끝날 때(AuctionFixtures.assertInvariants)와 부하 테스트 뒤 점검에 쓴다.
-- 블록은 "-- name: <이름>"으로 시작한다. 근거: docs/domains/auction/invariants.md, state-machines.md 6절

-- name: INV-AU-07 진행 중 경매의 최고 입찰은 확보돼 있다
SELECT a.auction_id FROM auctions a
JOIN auction_bids b ON b.auction_id = a.auction_id
 AND b.bid_sequence = (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = a.auction_id)
LEFT JOIN credit_holds h ON h.auction_bid_id = b.auction_bid_id AND h.status = 'HELD'
WHERE a.status <> 'CLOSED' AND h.credit_hold_id IS NULL;

-- name: INV-AU-08 추월된 입찰의 확보는 해제돼 있다
SELECT h.credit_hold_id FROM credit_holds h
JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
WHERE h.status = 'HELD'
  AND b.bid_sequence < (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = b.auction_id);

-- name: INV-AU-14 지갑의 묶인 금액은 HELD 확보 합계와 같다
SELECT w.credit_wallet_id FROM credit_wallets w
LEFT JOIN credit_holds h ON h.credit_wallet_id = w.credit_wallet_id AND h.status = 'HELD'
GROUP BY w.credit_wallet_id, w.reserved_amount
HAVING w.reserved_amount <> COALESCE(SUM(h.held_amount), 0);

-- name: INV-AU-15 지갑의 보유액·묶인 금액은 내역 합계와 같다
SELECT w.credit_wallet_id FROM credit_wallets w
LEFT JOIN credit_transactions t ON t.credit_wallet_id = w.credit_wallet_id
GROUP BY w.credit_wallet_id, w.balance_amount, w.reserved_amount
HAVING w.balance_amount <> COALESCE(SUM(t.balance_delta), 0)
    OR w.reserved_amount <> COALESCE(SUM(t.reserved_delta), 0);

-- name: INV-AU-03 다음 입찰은 이전 입찰 + 최소 인상액 이상
SELECT b2.auction_bid_id FROM auction_bids b1
JOIN auction_bids b2 ON b2.auction_id = b1.auction_id AND b2.bid_sequence = b1.bid_sequence + 1
JOIN auctions a ON a.auction_id = b1.auction_id
WHERE b2.bid_amount < b1.bid_amount + a.minimum_increment_amount;

-- name: CLOSED와 결과 행은 함께 있다
SELECT a.auction_id FROM auctions a LEFT JOIN auction_results r ON r.auction_id = a.auction_id
WHERE (a.status = 'CLOSED') <> (r.auction_id IS NOT NULL);

-- name: 종료된 경매에 HELD 확보가 없다
SELECT h.credit_hold_id FROM credit_holds h
JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
JOIN auctions a ON a.auction_id = b.auction_id
WHERE a.status = 'CLOSED' AND h.status = 'HELD';

-- name: INV-AU-13 SOLD는 마지막 입찰이고 그 확보는 CAPTURED
SELECT r.auction_id FROM auction_results r
LEFT JOIN auction_bids b ON b.auction_bid_id = r.winning_bid_id
LEFT JOIN credit_holds h ON h.auction_bid_id = r.winning_bid_id
WHERE r.result_type = 'SOLD'
  AND (b.bid_sequence <> (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = r.auction_id)
       OR h.status <> 'CAPTURED');

-- name: UNSOLD 경매에는 입찰이 없다
SELECT r.auction_id FROM auction_results r
WHERE r.result_type = 'UNSOLD' AND EXISTS (SELECT 1 FROM auction_bids b WHERE b.auction_id = r.auction_id);

-- name: INV-AU-11 종료 시각 이후 수락된 입찰이 없다
SELECT b.auction_bid_id FROM auction_bids b JOIN auctions a ON a.auction_id = b.auction_id
WHERE a.status = 'CLOSED' AND b.accepted_at >= a.ends_at;
