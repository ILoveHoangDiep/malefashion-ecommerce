package com.malefashion.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.payment.infrastructure.persistence.OutboxEvent;
import com.malefashion.payment.infrastructure.persistence.OutboxEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Ghi envelope vào outbox trong transaction của caller, để thay đổi trạng thái và event
 * cùng commit hoặc cùng rollback.
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
