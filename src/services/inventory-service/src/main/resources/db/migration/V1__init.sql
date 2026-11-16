CREATE TABLE stock_items
(
    variant_id         uuid        NOT NULL,
    sku                varchar(64) NOT NULL,
    available_quantity integer     NOT NULL,
    reserved_quantity  integer     NOT NULL,
    version            bigint      NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT pk_stock_items PRIMARY KEY (variant_id),
    CONSTRAINT uq_stock_items_sku UNIQUE (sku),
    CONSTRAINT ck_stock_items_available_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_stock_items_reserved_quantity CHECK (reserved_quantity >= 0)
);

CREATE TABLE stock_reservations
(
    id         uuid        NOT NULL,
    order_id   uuid        NOT NULL,
    status     varchar(20) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL,
    CONSTRAINT pk_stock_reservations PRIMARY KEY (id),
    CONSTRAINT uq_stock_reservations_order_id UNIQUE (order_id),
    CONSTRAINT ck_stock_reservations_status
        CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX idx_stock_reservations_status_expires_at
    ON stock_reservations (status, expires_at);

CREATE TABLE stock_reservation_items
(
    id             uuid        NOT NULL,
    reservation_id uuid        NOT NULL,
    variant_id     uuid        NOT NULL,
    sku            varchar(64) NOT NULL,
    quantity       integer     NOT NULL,
    CONSTRAINT pk_stock_reservation_items PRIMARY KEY (id),
    CONSTRAINT fk_stock_reservation_items_reservation
        FOREIGN KEY (reservation_id) REFERENCES stock_reservations (id) ON DELETE CASCADE,
    CONSTRAINT ck_stock_reservation_items_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_reservation_items_reservation_id
    ON stock_reservation_items (reservation_id);

CREATE TABLE outbox_events
(
    id           uuid         NOT NULL,
    event_type   varchar(100) NOT NULL,
    routing_key  varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,
    status       varchar(20)  NOT NULL,
    attempts     integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_status_created_at
    ON outbox_events (status, created_at);

CREATE TABLE processed_messages
(
    consumer     varchar(100) NOT NULL,
    message_id   uuid         NOT NULL,
    processed_at timestamptz  NOT NULL,
    CONSTRAINT pk_processed_messages PRIMARY KEY (consumer, message_id)
);
