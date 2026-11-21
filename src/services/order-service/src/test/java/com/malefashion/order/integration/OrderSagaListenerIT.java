package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.support.MessagingIntegrationTestBase;
import com.malefashion.order.support.RecordingSagaEventHandler;
import com.malefashion.order.support.RecordingSagaEventHandler.Handled;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Listener thật trên RabbitMQ thật: parse 8 contract example, dedupe theo eventId, lỗi vĩnh viễn
 * vào DLQ ngay, lỗi tạm thời retry đúng 3 lần rồi DLQ.
 */
@Import(OrderSagaListenerIT.HandlerConfig.class)
class OrderSagaListenerIT extends MessagingIntegrationTestBase {

    private static final List<ContractExample> CONTRACT_EXAMPLES = List.of(
            new ContractExample(MessageTypes.EVENT_STOCK_RESERVED, "inventory-stock-reserved.json"),
            new ContractExample(MessageTypes.EVENT_STOCK_RESERVATION_FAILED,
                    "inventory-stock-reservation-failed.json"),
            new ContractExample(MessageTypes.EVENT_STOCK_COMMITTED,
                    "inventory-stock-committed.json"),
            new ContractExample(MessageTypes.EVENT_STOCK_COMMIT_FAILED,
                    "inventory-stock-commit-failed.json"),
            new ContractExample(MessageTypes.EVENT_STOCK_RELEASED, "inventory-stock-released.json"),
            new ContractExample(MessageTypes.EVENT_PAYMENT_SUCCEEDED, "payment-succeeded.json"),
            new ContractExample(MessageTypes.EVENT_PAYMENT_FAILED, "payment-failed.json"),
            new ContractExample(MessageTypes.EVENT_PAYMENT_REFUNDED, "payment-refunded.json"));

    @Autowired
    private RecordingSagaEventHandler recordingHandler;

    @BeforeEach
    void resetHandler() {
        recordingHandler.reset();
    }

    @Test
    void consumesAllEightContractExamples() throws IOException {
        for (ContractExample example : CONTRACT_EXAMPLES) {
            JsonNode body = firstExample(example.schemaFile());
            UUID eventId = UUID.fromString(body.path("eventId").asText());
            sendRawEvent(example.eventType(), eventId, json(body));
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> processedMessageCount() == CONTRACT_EXAMPLES.size());

        assertThat(recordingHandler.attempts()).isEqualTo(CONTRACT_EXAMPLES.size());
        assertThat(recordingHandler.handled())
                .extracting(Handled::eventType)
                .containsExactlyInAnyOrderElementsOf(
                        CONTRACT_EXAMPLES.stream().map(ContractExample::eventType).toList());
        assertThat(recordingHandler.handled()).allSatisfy(handled -> {
            assertThat(handled.mdcTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(handled.mdcOrderId())
                    .isEqualTo("5d1c9b2a-8e4f-4a6b-b1c2-3d4e5f6a7b8c");
            assertThat(handled.mdcEventId()).isEqualTo(handled.eventId().toString());
        });
    }

    @Test
    void duplicateEventIdIsProcessedOnce() throws IOException {
        JsonNode body = firstExample("inventory-stock-reserved.json");
        UUID eventId = UUID.fromString(body.path("eventId").asText());
        String json = json(body);

        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, eventId, json);
        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, eventId, json);

        awaitProcessed(eventId);
        awaitQueueEmpty(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(2))
                .until(() -> recordingHandler.attempts() == 1);
        assertThat(markerCount(eventId)).isEqualTo(1);
    }

    @Test
    void malformedJsonIsDeadLetteredWithoutHandler() {
        UUID eventId = UUID.randomUUID();

        sendRaw(MessageTypes.EVENT_STOCK_RESERVED, MessageTypes.EVENT_STOCK_RESERVED, eventId,
                "{not valid json");

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isZero();
        assertThat(processedMessageCount(eventId)).isZero();
        assertNoFurtherDelivery(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
    }

    @Test
    void headerBodyTypeMismatchIsDeadLetteredWithoutHandler() throws IOException {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = (ObjectNode) firstExample("inventory-stock-committed.json");
        body.put("eventId", eventId.toString());

        sendRaw(MessageTypes.EVENT_STOCK_RESERVED, MessageTypes.EVENT_STOCK_RESERVED, eventId,
                json(body));

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isZero();
        assertThat(processedMessageCount(eventId)).isZero();
    }

    @Test
    void missingRequiredFieldIsDeadLetteredWithoutHandler() throws IOException {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = (ObjectNode) firstExample("inventory-stock-reserved.json");
        body.put("eventId", eventId.toString());
        body.remove("correlationId");

        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, eventId, json(body));

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isZero();
        assertThat(processedMessageCount(eventId)).isZero();
    }

    @Test
    void unsupportedEventTypeIsDeadLetteredWithoutHandler() throws IOException {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = (ObjectNode) firstExample("inventory-stock-reserved.json");
        body.put("eventId", eventId.toString());
        body.put("eventType", "inventory.not-a-saga-event");

        // Routing key hợp lệ để message vào queue; type header + body là loại không hỗ trợ.
        sendRaw(MessageTypes.EVENT_STOCK_RESERVED, "inventory.not-a-saga-event", eventId,
                json(body));

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isZero();
        assertThat(processedMessageCount(eventId)).isZero();
    }

    @Test
    void wrongPayloadFieldTypeIsDeadLetteredWithoutHandler() throws IOException {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = (ObjectNode) firstExample("inventory-stock-reserved.json");
        body.put("eventId", eventId.toString());
        ((ObjectNode) body.get("payload").get("items").get(0)).put("quantity", "two");

        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, eventId, json(body));

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isZero();
        assertThat(processedMessageCount(eventId)).isZero();
    }

    @Test
    void temporaryFailureIsRetriedThreeTimesThenDeadLettered() throws IOException {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = (ObjectNode) firstExample("inventory-stock-reserved.json");
        body.put("eventId", eventId.toString());
        recordingHandler.failWith(
                new DataAccessResourceFailureException("simulated database outage"));

        sendRawEvent(MessageTypes.EVENT_STOCK_RESERVED, eventId, json(body));

        awaitDeadLettered(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        assertThat(recordingHandler.attempts()).isEqualTo(3);
        // Marker rollback cùng nghiệp vụ nên không có dấu vết nào được commit.
        assertThat(processedMessageCount(eventId)).isZero();
        assertNoFurtherDelivery(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
    }

    private long markerCount(UUID eventId) {
        return processedMessageCount(eventId);
    }

    private record ContractExample(String eventType, String schemaFile) {
    }

    @TestConfiguration
    static class HandlerConfig {

        @Bean
        @Primary
        RecordingSagaEventHandler recordingSagaEventHandler() {
            return new RecordingSagaEventHandler();
        }
    }
}
