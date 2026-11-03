-- ============================================================
-- V1: Initialize catalog schema – categories, products, variants
-- ============================================================

-- ---------- categories ----------
CREATE TABLE categories (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL UNIQUE,
    slug        VARCHAR(120)    NOT NULL UNIQUE,
    description TEXT,
    parent_id   UUID            REFERENCES categories(id),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- ---------- products ----------
CREATE TABLE products (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    slug        VARCHAR(280)    NOT NULL UNIQUE,
    description TEXT,
    category_id UUID            REFERENCES categories(id),
    active      BOOLEAN         NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- ---------- variants ----------
CREATE TABLE variants (
    id          UUID            PRIMARY KEY,
    product_id  UUID            NOT NULL REFERENCES products(id),
    sku         VARCHAR(50)     NOT NULL UNIQUE,
    size        VARCHAR(5)      NOT NULL,
    color       VARCHAR(50)     NOT NULL,
    price       NUMERIC(15, 2)  NOT NULL,
    image_url   VARCHAR(512),
    active      BOOLEAN         NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_variant_product_size_color UNIQUE (product_id, size, color)
);

-- ---------- indexes ----------
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_slug        ON products(slug);
CREATE INDEX idx_variants_product_id  ON variants(product_id);
CREATE INDEX idx_variants_sku         ON variants(sku);
