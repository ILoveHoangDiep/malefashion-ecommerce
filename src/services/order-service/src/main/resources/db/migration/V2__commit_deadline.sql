-- Hạn chờ commit: step AWAITING_COMMIT trước đây không có deadline nên đơn đã thu tiền có thể kẹt
-- mãi nếu inventory chậm hoặc lệnh commit vào DLQ. Retry dùng lại đúng commit_command_event_id.
ALTER TABLE orders
    ADD COLUMN commit_command_event_id uuid,
    ADD COLUMN commit_retry_count      integer     NOT NULL DEFAULT 0,
    ADD COLUMN commit_deadline_at      timestamptz(3),
    ADD COLUMN commit_unconfirmed      boolean     NOT NULL DEFAULT FALSE;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_commit_retry_count CHECK (commit_retry_count >= 0);

CREATE INDEX idx_orders_commit_deadline
    ON orders (commit_deadline_at)
    WHERE saga_step = 'AWAITING_COMMIT';
