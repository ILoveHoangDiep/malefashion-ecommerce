package com.malefashion.inventory.infrastructure.messaging.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockReservationFailedPayload(
        UUID orderId,
        ReservationFailureReason reason,
        List<FailedItem> failedItems,
        Instant failedAt
) {

    public record FailedItem(
            UUID variantId,
            String sku,
            int requestedQuantity,
            int availableQuantity
    ) {
    }
}
