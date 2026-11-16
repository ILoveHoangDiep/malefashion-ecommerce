package com.malefashion.inventory.api;

import java.util.UUID;

/**
 * Batch item shape from the contract: no updatedAt and no internal fields.
 */
public record StockSummaryResponse(
        UUID variantId,
        String sku,
        int availableQuantity,
        boolean inStock
) {
}
