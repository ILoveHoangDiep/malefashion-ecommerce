package com.malefashion.order.infrastructure.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope dùng chung cho command và event. Command cũng có {@code eventId} riêng để phía nhận
 * dedupe; {@code correlationId} luôn là orderId của saga.
 */
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

    public MessageEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(producer, "producer must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }
}
