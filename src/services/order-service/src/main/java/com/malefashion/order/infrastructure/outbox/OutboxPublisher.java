package com.malefashion.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.domain.support.Instants;
import com.malefashion.order.infrastructure.messaging.TraceIds;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publish các dòng PENDING và chỉ đánh dấu SENT sau khi broker ack trong
 * {@code order.outbox.confirm-timeout}. nack/return/timeout/exception chỉ tăng attempts và giữ
 * PENDING; các dòng còn lại trong batch vẫn được xử lý độc lập.
 *
 * <p>Đảm bảo at-least-once: nếu broker đã nhận nhưng ack bị mất, dòng vẫn PENDING và lần sau được
 * publish lại. Thứ tự chỉ là tương đối theo {@code created_at}; nhiều publisher hoặc một dòng lỗi
 * có thể làm event sau tới trước. Consumer phải chịu được duplicate và không dựa vào thứ tự.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "order.outbox.publisher",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final OrderProperties properties;
    private final Clock clock;

    public OutboxPublisher(PlatformTransactionManager transactionManager,
                           OutboxEventRepository outboxEventRepository,
                           RabbitTemplate rabbitTemplate,
                           ObjectMapper objectMapper,
                           OrderProperties properties,
                           Clock clock) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${order.outbox.publish-interval-ms}",
            initialDelayString = "${order.outbox.publish-interval-ms}")
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
                event.markSent(Instants.millis(clock.instant()));
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
            // messageId là eventId trong envelope, KHÔNG phải id kỹ thuật của dòng outbox.
            messageProperties.setMessageId(event.getEventId().toString());
            messageProperties.setType(event.getEventType());
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            messageProperties.setCorrelationId(event.getAggregateId().toString());
            messageProperties.setHeader("x-event-version",
                    envelope.path("eventVersion").asInt(1));

            String traceId = envelope.path("traceId").asText("");
            if (TraceIds.isValid(traceId)) {
                messageProperties.setHeader("traceparent", TraceIds.traceparent(traceId));
            } else {
                log.warn("Outbox row {} for eventId={} has no valid traceId; publishing without "
                        + "traceparent", event.getId(), event.getEventId());
            }

            Message message = new Message(objectMapper.writeValueAsBytes(envelope),
                    messageProperties);
            CorrelationData correlationData = new CorrelationData(event.getEventId().toString());

            rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message,
                    correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.outbox().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.warn("Broker did not ack orderId={} eventId={} eventType={}: {}",
                        event.getAggregateId(), event.getEventId(), event.getEventType(),
                        confirm == null ? "no confirm" : confirm.getReason());
                return false;
            }
            if (correlationData.getReturned() != null) {
                ReturnedMessage returned = correlationData.getReturned();
                log.warn("Broker returned unroutable orderId={} eventId={} eventType={}: "
                                + "exchange={}, routingKey={}, replyCode={} {}",
                        event.getAggregateId(), event.getEventId(), event.getEventType(),
                        returned.getExchange(), returned.getRoutingKey(),
                        returned.getReplyCode(), returned.getReplyText());
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for broker confirm orderId={} eventId={}",
                    event.getAggregateId(), event.getEventId());
            return false;
        } catch (Exception failure) {
            log.warn("Publishing failed orderId={} eventId={} eventType={}: {}",
                    event.getAggregateId(), event.getEventId(), event.getEventType(),
                    failure.toString());
            return false;
        }
    }
}
