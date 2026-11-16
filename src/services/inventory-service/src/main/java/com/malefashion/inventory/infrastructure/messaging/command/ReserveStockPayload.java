package com.malefashion.inventory.infrastructure.messaging.command;

import java.util.List;
import java.util.UUID;

public record ReserveStockPayload(
        UUID orderId,
        List<Item> items
) {

    public record Item(
            UUID variantId,
            String sku,
            int quantity
    ) {
    }
}
