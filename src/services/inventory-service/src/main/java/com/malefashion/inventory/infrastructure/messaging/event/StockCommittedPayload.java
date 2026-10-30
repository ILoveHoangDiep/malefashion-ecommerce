package com.malefashion.inventory.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record StockCommittedPayload(
        UUID orderId,
        UUID reservationId,
        Instant committedAt
) {
}
