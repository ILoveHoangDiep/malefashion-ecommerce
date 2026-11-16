package com.malefashion.inventory.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.infrastructure.messaging.MessagingTopology;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes PENDING outbox rows to the events exchange and only marks them SENT once the broker
 * confirmed the publish. Rows are claimed with {@code FOR UPDATE SKIP LOCKED} so parallel instances
 * do not publish the same row.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryProperties properties;

    public OutboxPublisher(PlatformTransactionManager transactionManager,
                           OutboxEventRepository outboxEventRepository,
                           RabbitTemplate rabbitTemplate,
                           ObjectMapper objectMapper,
                           InventoryProperties properties) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${inventory.outbox.publish-interval-ms}",
            initialDelayString = "${inventory.outbox.initial-delay-ms}")
    public void publishPendingEvents() {
        transactionTemplate.executeWithoutResult(status -> publishBatch());
    }

    private void publishBatch() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingForUpdate(
                properties.outbox().batchSize());
        if (pending.isEmpty()) {
            return;
        }

        int sent = 0;
        for (OutboxEvent event : pending) {
            if (publish(event)) {
                event.markSent(Instant.now());
                sent++;
            } else {
                event.recordAttemptFailure();
            }
        }
        log.info("Outbox batch finished: {} sent, {} kept PENDING", sent, pending.size() - sent);
    }

    private boolean publish(OutboxEvent event) {
        try {
            JsonNode envelope = event.getPayload();
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setMessageId(event.getId().toString());
            messageProperties.setType(event.getEventType());
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            messageProperties.setCorrelationId(envelope.path("correlationId").asText());
            messageProperties.setHeader("x-event-version", envelope.path("eventVersion").asInt(1));

            Message message = new Message(objectMapper.writeValueAsBytes(envelope), messageProperties);
            CorrelationData correlationData = new CorrelationData(event.getId().toString());

            rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, event.getRoutingKey(), message,
                    correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.outbox().confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.warn("Broker did not ack event {} ({}): {}", event.getId(), event.getEventType(),
                        confirm == null ? "no confirm" : confirm.getReason());
                return false;
            }
            if (correlationData.getReturned() != null) {
                ReturnedMessage returned = correlationData.getReturned();
                log.warn("Broker returned unroutable event {} ({}): exchange={}, routingKey={}, replyCode={} {}",
                        event.getId(), event.getEventType(), returned.getExchange(), returned.getRoutingKey(),
                        returned.getReplyCode(), returned.getReplyText());
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for broker confirm of event {}", event.getId());
            return false;
        } catch (Exception failure) {
            log.warn("Publishing event {} ({}) failed: {}", event.getId(), event.getEventType(),
                    failure.toString());
            return false;
        }
    }
}
