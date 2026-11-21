package com.malefashion.order.infrastructure.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event saga đã parse: envelope + payload đã kiểu hoá. {@code orderId} lấy từ correlationId và đã
 * được đối chiếu với payload.orderId.
 */
public record InboundEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID orderId,
        String traceId,
        SagaEventPayload payload
) {

    public InboundEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(producer, "producer must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
