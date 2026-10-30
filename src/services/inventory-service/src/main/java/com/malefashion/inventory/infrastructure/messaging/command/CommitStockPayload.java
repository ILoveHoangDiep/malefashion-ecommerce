package com.malefashion.inventory.infrastructure.messaging.command;

import java.util.UUID;

public record CommitStockPayload(
        UUID orderId,
        UUID reservationId
) {
}
