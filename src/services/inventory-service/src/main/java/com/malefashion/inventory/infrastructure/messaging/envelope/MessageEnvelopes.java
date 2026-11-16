package com.malefashion.inventory.infrastructure.messaging.envelope;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MessageEnvelopes {

    public static final String PRODUCER = "inventory-service";
    public static final int EVENT_VERSION = 1;

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private MessageEnvelopes() {
    }

    public static <T> MessageEnvelope<T> create(String eventType,
                                                UUID correlationId,
                                                String traceId,
                                                T payload) {
        return new MessageEnvelope<>(
                UUID.randomUUID(),
                eventType,
                EVENT_VERSION,
                Instant.now(),
                PRODUCER,
                correlationId,
                normalizeTraceId(traceId),
                payload
        );
    }

    /**
     * Re-emits an event that already has an identity, e.g. while replaying a reservation.
     */
    public static <T> MessageEnvelope<T> createWithNewEventId(String eventType,
                                                              UUID correlationId,
                                                              String traceId,
                                                              T payload) {
        return create(eventType, correlationId, traceId, payload);
    }

    public static String normalizeTraceId(String traceId) {
        if (traceId != null && TRACE_ID.matcher(traceId).matches()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
