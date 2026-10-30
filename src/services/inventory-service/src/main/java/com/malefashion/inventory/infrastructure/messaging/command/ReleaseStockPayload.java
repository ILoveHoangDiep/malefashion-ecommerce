package com.malefashion.inventory.infrastructure.messaging.command;

import java.util.UUID;

public record ReleaseStockPayload(
        UUID orderId,
        UUID reservationId,
        ReleaseReason reason
) {
}
