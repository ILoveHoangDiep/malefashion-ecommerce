package com.malefashion.inventory.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.OutboxEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Writes an event envelope to the outbox inside the caller's transaction, so the state change and the
 * event either commit together or not at all.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void append(MessageEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        JsonNode payload = objectMapper.valueToTree(envelope);
        outboxEventRepository.save(OutboxEvent.pending(
                envelope.eventId(),
                envelope.eventType(),
                envelope.eventType(),
                payload,
                Instant.now()
        ));
    }
}
