-- =============================================================
-- V2: Seed initial test and standard coupons
-- =============================================================

INSERT INTO coupons (
    id, code, description, discount_type, discount_value, min_order_amount,
    max_discount_amount, usage_limit, used_count, active, starts_at, expires_at, created_at, updated_at
) VALUES
(
    '11111111-1111-1111-1111-111111111111',
    'SUMMER10',
    'Giảm 10% tối đa 50k',
    'PERCENTAGE',
    10.00,
    200000.00,
    50000.00,
    1000,
    0,
    TRUE,
    now() - interval '7 days',
    now() + interval '90 days',
    now(),
    now()
),
(
    '22222222-2222-2222-2222-222222222222',
    'WELCOME20',
    'Giảm 20% cho đơn từ 300k',
    'PERCENTAGE',
    20.00,
    300000.00,
    100000.00,
    500,
    0,
    TRUE,
    now() - interval '7 days',
    now() + interval '90 days',
    now(),
    now()
),
(
    '33333333-3333-3333-3333-333333333333',
    'FREESHIP',
    'Miễn phí vận chuyển 30k',
    'FIXED_AMOUNT',
    30000.00,
    150000.00,
    NULL,
    NULL,
    0,
    TRUE,
    now() - interval '7 days',
    now() + interval '90 days',
    now(),
    now()
)
ON CONFLICT (code) DO NOTHING;
