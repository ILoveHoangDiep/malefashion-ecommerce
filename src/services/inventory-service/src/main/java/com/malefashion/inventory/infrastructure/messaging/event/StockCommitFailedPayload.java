package com.malefashion.inventory.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record StockCommitFailedPayload(
        UUID orderId,
        UUID reservationId,
        CommitFailureReason reason,
        Instant failedAt
) {
}
