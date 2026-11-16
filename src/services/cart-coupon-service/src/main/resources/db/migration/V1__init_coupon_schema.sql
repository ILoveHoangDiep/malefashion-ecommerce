-- =============================================================
-- V1: Coupon schema – coupons + coupon_usages
-- =============================================================

CREATE TABLE coupons (
    id                  UUID            PRIMARY KEY,
    code                VARCHAR(50)     NOT NULL UNIQUE,
    description         TEXT,
    discount_type       VARCHAR(20)     NOT NULL,          -- 'FIXED_AMOUNT' or 'PERCENTAGE'
    discount_value      NUMERIC(15, 2)  NOT NULL,
    min_order_amount    NUMERIC(15, 2)  DEFAULT 0,
    max_discount_amount NUMERIC(15, 2),                    -- cap cho PERCENTAGE, nullable
    usage_limit         INTEGER,                           -- null = unlimited
    used_count          INTEGER         DEFAULT 0,
    active              BOOLEAN         DEFAULT TRUE,
    starts_at           TIMESTAMPTZ     NOT NULL,
    expires_at          TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     DEFAULT now(),
    updated_at          TIMESTAMPTZ     DEFAULT now()
);

CREATE TABLE coupon_usages (
    id          UUID        PRIMARY KEY,
    coupon_id   UUID        NOT NULL REFERENCES coupons (id),
    user_id     UUID        NOT NULL,
    order_id    UUID,                                       -- nullable – set when order is placed
    used_at     TIMESTAMPTZ DEFAULT now(),

    CONSTRAINT uq_coupon_user UNIQUE (coupon_id, user_id)   -- mỗi user chỉ dùng 1 mã 1 lần
);

-- ── Indexes ──────────────────────────────────────────────────
CREATE INDEX idx_coupons_code              ON coupons (code);
CREATE INDEX idx_coupons_active_dates      ON coupons (active, starts_at, expires_at);
CREATE INDEX idx_coupon_usages_user_id     ON coupon_usages (user_id);
