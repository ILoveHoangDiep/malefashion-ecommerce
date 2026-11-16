CREATE SEQUENCE payment_provider_transaction_id_seq
    START WITH 1
    INCREMENT BY 1;

CREATE TABLE payments
(
    id                      uuid           NOT NULL,
    order_id                uuid           NOT NULL,
    amount                  numeric(19, 2) NOT NULL,
    currency                varchar(3)     NOT NULL,
    status                  varchar(20)    NOT NULL,
    scenario                varchar(20),
    provider_transaction_id varchar(64),
    failure_code            varchar(50),
    failure_message         varchar(500),
    paid_at                 timestamptz,
    failed_at               timestamptz,
    created_at              timestamptz    NOT NULL,
    updated_at              timestamptz    NOT NULL,
    version                 bigint         NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT uq_payments_provider_transaction_id UNIQUE (provider_transaction_id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_payments_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'REFUNDED'))
);

CREATE INDEX idx_payments_status_created_at
    ON payments (status, created_at);

CREATE TABLE refunds
(
    id          uuid           NOT NULL,
    payment_id  uuid           NOT NULL,
    order_id    uuid           NOT NULL,
    amount      numeric(19, 2) NOT NULL,
    reason      varchar(100)   NOT NULL,
    refunded_at timestamptz    NOT NULL,
    created_at  timestamptz    NOT NULL,
    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_refunds_payment_id UNIQUE (payment_id),
    CONSTRAINT uq_refunds_order_id UNIQUE (order_id),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0)
);

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
