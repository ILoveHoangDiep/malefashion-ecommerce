package com.malefashion.inventory.infrastructure.messaging.envelope;

import java.time.Instant;
import java.util.UUID;

public record MessageEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        String traceId,
        T payload
) {
}
