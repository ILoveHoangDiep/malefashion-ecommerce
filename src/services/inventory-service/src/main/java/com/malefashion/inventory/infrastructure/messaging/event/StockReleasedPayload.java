package com.malefashion.inventory.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record StockReleasedPayload(
        UUID orderId,
        UUID reservationId,
        ReleaseEventReason reason,
        Instant releasedAt
) {
}
