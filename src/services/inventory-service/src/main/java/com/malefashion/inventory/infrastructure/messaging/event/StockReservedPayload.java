package com.malefashion.inventory.infrastructure.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockReservedPayload(
        UUID orderId,
        UUID reservationId,
        Instant expiresAt,
        List<Item> items
) {

    public record Item(
            UUID variantId,
            String sku,
            int quantity
    ) {
    }
}
