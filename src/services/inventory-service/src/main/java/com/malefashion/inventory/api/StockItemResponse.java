package com.malefashion.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of one stock row. Deliberately omits reservedQuantity and version.
 */
public record StockItemResponse(
        UUID variantId,
        String sku,
        int availableQuantity,
        boolean inStock,
        Instant updatedAt
) {
}
