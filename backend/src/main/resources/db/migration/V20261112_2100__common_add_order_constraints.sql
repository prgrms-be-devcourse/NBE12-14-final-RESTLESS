-- Common order status and type are enforced by the database as a last line of defense.
-- Merged V20261111_0920 is not edited.

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN (
        'PENDING_PAYMENT', 'PAYMENT_CONFIRMING', 'PAYMENT_REVIEW', 'PAID',
        'CANCELLED', 'REFUND_PENDING', 'REFUND_REVIEW', 'REFUNDED')),
    ADD CONSTRAINT ck_orders_order_type CHECK (order_type IN ('DROP', 'TICKET')),
    -- My orders list: newest first per user.
    ADD INDEX idx_orders_user_created_at (user_id, created_at);
