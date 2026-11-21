CREATE TABLE orders
(
    id                      uuid           NOT NULL,
    order_number            varchar(24)    NOT NULL,
    user_id                 uuid           NOT NULL,
    user_email              varchar(320)   NOT NULL,
    customer_name           varchar(200)   NOT NULL,
    status                  varchar(20)    NOT NULL,
    cancel_reason           varchar(40),
    pending_cancel_reason   varchar(40),
    saga_step               varchar(30)    NOT NULL,
    refund_state            varchar(20)    NOT NULL DEFAULT 'NONE',
    refund_command_event_id uuid,
    refund_retry_count      integer        NOT NULL DEFAULT 0,
    refund_deadline_at      timestamptz(3),
    currency                varchar(3)     NOT NULL,
    subtotal                numeric(18, 2) NOT NULL,
    discount_amount         numeric(18, 2) NOT NULL,
    shipping_fee            numeric(18, 2) NOT NULL,
    total_amount            numeric(18, 2) NOT NULL,
    coupon_code             varchar(50),
    mock_payment_scenario   varchar(20),
    shipping_recipient_name varchar(200)   NOT NULL,
    shipping_phone          varchar(20)    NOT NULL,
    shipping_address_line   varchar(300)   NOT NULL,
    shipping_ward           varchar(100)   NOT NULL,
    shipping_district       varchar(100)   NOT NULL,
    shipping_city           varchar(100)   NOT NULL,
    reservation_id          uuid,
    reservation_expires_at  timestamptz(3),
    payment_id              uuid,
    paid_amount             numeric(18, 2),
    provider_transaction_id varchar(64),
    refund_id               uuid,
    saga_deadline_at        timestamptz(3),
    compensation_deadline_at timestamptz(3),
    version                 bigint         NOT NULL,
    created_at              timestamptz(3) NOT NULL,
    updated_at              timestamptz(3) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_order_number
        CHECK (order_number ~ '^MF-[0-9]{8}-[A-Z0-9]{6}$'),
    CONSTRAINT ck_orders_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_orders_cancel_reason
        CHECK (cancel_reason IS NULL OR cancel_reason IN
               ('OUT_OF_STOCK', 'PAYMENT_FAILED', 'SAGA_TIMEOUT', 'STOCK_COMMIT_FAILED')),
    CONSTRAINT ck_orders_pending_cancel_reason
        CHECK (pending_cancel_reason IS NULL OR pending_cancel_reason IN
               ('OUT_OF_STOCK', 'PAYMENT_FAILED', 'SAGA_TIMEOUT', 'STOCK_COMMIT_FAILED')),
    CONSTRAINT ck_orders_saga_step
        CHECK (saga_step IN ('AWAITING_RESERVATION', 'AWAITING_PAYMENT', 'AWAITING_COMMIT',
                             'AWAITING_RELEASE', 'AWAITING_REFUND', 'DONE')),
    CONSTRAINT ck_orders_refund_state
        CHECK (refund_state IN ('NONE', 'REQUESTED', 'REFUNDED', 'REFUND_FAILED')),
    CONSTRAINT ck_orders_refund_retry_count CHECK (refund_retry_count >= 0),
    CONSTRAINT ck_orders_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_orders_subtotal CHECK (subtotal >= 0),
    CONSTRAINT ck_orders_discount_amount CHECK (discount_amount >= 0),
    CONSTRAINT ck_orders_shipping_fee CHECK (shipping_fee >= 0),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_paid_amount CHECK (paid_amount IS NULL OR paid_amount >= 0),
    CONSTRAINT ck_orders_mock_payment_scenario
        CHECK (mock_payment_scenario IS NULL
               OR mock_payment_scenario IN ('SUCCESS', 'FAILURE', 'TIMEOUT'))
);

CREATE INDEX idx_orders_user_id_created_at
    ON orders (user_id, created_at DESC);

CREATE INDEX idx_orders_saga_deadline
    ON orders (saga_deadline_at)
    WHERE saga_step IN ('AWAITING_RESERVATION', 'AWAITING_PAYMENT');

CREATE INDEX idx_orders_compensation_deadline
    ON orders (compensation_deadline_at)
    WHERE saga_step = 'AWAITING_RELEASE';

CREATE INDEX idx_orders_refund_deadline
    ON orders (refund_deadline_at)
    WHERE refund_state = 'REQUESTED';

CREATE TABLE order_items
(
    id           uuid           NOT NULL,
    order_id     uuid           NOT NULL,
    variant_id   uuid           NOT NULL,
    sku          varchar(64)    NOT NULL,
    product_name varchar(200)   NOT NULL,
    size         varchar(1)     NOT NULL,
    color        varchar(50)    NOT NULL,
    quantity     integer        NOT NULL,
    unit_price   numeric(18, 2) NOT NULL,
    line_total   numeric(18, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_items_size CHECK (size IN ('S', 'M', 'L')),
    CONSTRAINT ck_order_items_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_line_total CHECK (line_total >= 0)
);

CREATE INDEX idx_order_items_order_id
    ON order_items (order_id);

CREATE TABLE checkout_idempotency
(
    id              uuid           NOT NULL,
    user_id         uuid           NOT NULL,
    idempotency_key uuid           NOT NULL,
    request_hash    varchar(64)    NOT NULL,
    order_id        uuid           NOT NULL,
    created_at      timestamptz(3) NOT NULL,
    CONSTRAINT pk_checkout_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_checkout_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_checkout_idempotency_order
        FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_checkout_idempotency_order_id
    ON checkout_idempotency (order_id);

-- id là khóa kỹ thuật của dòng outbox; event_id là eventId trong envelope/AMQP messageId.
-- event_id KHÔNG unique: retry refund chèn dòng mới nhưng dùng lại đúng eventId cũ.
CREATE TABLE outbox_events
(
    id           uuid           NOT NULL,
    event_id     uuid           NOT NULL,
    event_type   varchar(100)   NOT NULL,
    aggregate_id uuid           NOT NULL,
    exchange     varchar(100)   NOT NULL,
    routing_key  varchar(100)   NOT NULL,
    payload      jsonb          NOT NULL,
    status       varchar(20)    NOT NULL,
    attempts     integer        NOT NULL DEFAULT 0,
    created_at   timestamptz(3) NOT NULL,
    published_at timestamptz(3),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_events_exchange
        CHECK (exchange IN ('malefashion.events', 'malefashion.commands')),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_pending_created_at
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_events_event_id
    ON outbox_events (event_id);

CREATE INDEX idx_outbox_events_aggregate_id
    ON outbox_events (aggregate_id);

CREATE TABLE processed_messages
(
    consumer     varchar(100)   NOT NULL,
    message_id   uuid           NOT NULL,
    processed_at timestamptz(3) NOT NULL,
    CONSTRAINT pk_processed_messages PRIMARY KEY (consumer, message_id)
);
