package com.malefashion.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.domain.support.Instants;
import com.malefashion.order.infrastructure.messaging.MessageEnvelope;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Ghi envelope vào outbox trong transaction của caller. Propagation MANDATORY bắt buộc thay đổi
 * trạng thái order, marker inbox và outbox event cùng commit hoặc cùng rollback.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent appendCommand(MessageEnvelope<?> envelope) {
        return append(envelope, MessagingTopology.COMMANDS_EXCHANGE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent appendEvent(MessageEnvelope<?> envelope) {
        return append(envelope, MessagingTopology.EVENTS_EXCHANGE);
    }

    private OutboxEvent append(MessageEnvelope<?> envelope, String exchange) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        JsonNode payload = objectMapper.valueToTree(envelope);
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                envelope.eventId(),
                envelope.eventType(),
                envelope.correlationId(),
                exchange,
                envelope.eventType(),
                payload,
                Instants.millis(clock.instant()));
        return outboxEventRepository.save(event);
    }
}
