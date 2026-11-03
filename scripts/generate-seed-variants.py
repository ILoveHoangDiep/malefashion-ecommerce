#!/usr/bin/env python3
"""Sinh contracts/seed/variants.json một cách xác định (chạy lại ra y hệt).

10 product x 3 size (S, M, L) x 2 color (BLACK, WHITE) = 60 variant.
productId/variantId dùng uuid5 với namespace cố định; SKU theo MF-{PRODUCT}-{COLOR}-{SIZE}.
"""

import hashlib
import json
import uuid
from pathlib import Path

NAMESPACE = uuid.UUID("d7f0a4c2-9b3e-4f61-8a2d-5c1e7b9f4a30")
SIZES = ("S", "M", "L")
COLORS = ("BLACK", "WHITE")

PRODUCTS = (
    ("basic-tee", "TEE", "Basic Tee", 299000),
    ("oxford-shirt", "OXFORD", "Oxford Shirt", 459000),
    ("polo-shirt", "POLO", "Polo Shirt", 399000),
    ("hoodie", "HOODIE", "Hoodie", 599000),
    ("bomber-jacket", "JACKET", "Bomber Jacket", 899000),
    ("slim-jeans", "JEANS", "Slim Jeans", 699000),
    ("chino-pants", "CHINO", "Chino Pants", 549000),
    ("midi-dress", "DRESS", "Midi Dress", 749000),
    ("pleated-skirt", "SKIRT", "Pleated Skirt", 499000),
    ("cargo-shorts", "SHORTS", "Cargo Shorts", 449000),
)


def stable_stock(sku: str) -> int:
    digest = hashlib.sha256(sku.encode("utf-8")).hexdigest()
    return 20 + int(digest[:8], 16) % 31


def build_seed() -> dict:
    products = []
    for slug, token, name, price in PRODUCTS:
        product_id = uuid.uuid5(NAMESPACE, f"product:{slug}")
        variants = []
        for size in SIZES:
            for color in COLORS:
                sku = f"MF-{token}-{color}-{size}"
                variants.append(
                    {
                        "variantId": str(uuid.uuid5(NAMESPACE, f"variant:{sku}")),
                        "sku": sku,
                        "size": size,
                        "color": color,
                        "price": {"amount": f"{price}.00", "currency": "VND"},
                        "imageUrl": (
                            "http://localhost:9000/malefashion-products/"
                            f"{slug}-{color.lower()}-{size.lower()}.jpg"
                        ),
                        "initialStock": stable_stock(sku),
                    }
                )
        products.append(
            {
                "productId": str(product_id),
                "productName": name,
                "variants": variants,
            }
        )
    return {"version": 1, "products": products}


def main() -> None:
    output = Path(__file__).resolve().parent.parent / "contracts" / "seed" / "variants.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(build_seed(), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {output}")


if __name__ == "__main__":
    main()
