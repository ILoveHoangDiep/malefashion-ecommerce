package com.malefashion.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.domain.saga.StockReleaseReason;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentRefunded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentSucceeded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitted;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReleased;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReservationFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReserved;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.TestObjectMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundEventParserTest {

    private static final String STOCK_RESERVED_FILE = "inventory-stock-reserved.json";
    private static final String RESERVATION_FAILED_FILE = "inventory-stock-reservation-failed.json";
    private static final String STOCK_COMMITTED_FILE = "inventory-stock-committed.json";
    private static final String STOCK_COMMIT_FAILED_FILE = "inventory-stock-commit-failed.json";
    private static final String STOCK_RELEASED_FILE = "inventory-stock-released.json";
    private static final String PAYMENT_SUCCEEDED_FILE = "payment-succeeded.json";
    private static final String PAYMENT_FAILED_FILE = "payment-failed.json";
    private static final String PAYMENT_REFUNDED_FILE = "payment-refunded.json";

    private final ObjectMapper objectMapper = TestObjectMappers.contract();
    private final InboundEventParser parser = new InboundEventParser(objectMapper);

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("contractExamples")
    void parsesEveryInboundContractExample(String eventType, String schemaFile,
                                           Class<?> payloadType) throws IOException {
        JsonNode example = ContractSchemas.firstExample(objectMapper, schemaFile);

        InboundEvent event = parser.parse(message(eventType, json(example)));

        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.orderId().toString())
                .isEqualTo(example.path("payload").path("orderId").asText());
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(event.payload()).isInstanceOf(payloadType);
    }

    @Test
    void fallsBackToBodyEventTypeWhenHeaderIsMissing() throws IOException {
        JsonNode example = ContractSchemas.firstExample(objectMapper, STOCK_RESERVED_FILE);

        InboundEvent event = parser.parse(message(null, json(example)));

        assertThat(event.payload()).isInstanceOf(StockReserved.class);
        StockReserved reserved = (StockReserved) event.payload();
        assertThat(reserved.items()).hasSize(1);
        assertThat(reserved.items().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void parsesMoneyAndTypedReasons() throws IOException {
        InboundEvent succeeded = parser.parse(message(MessageTypes.EVENT_PAYMENT_SUCCEEDED,
                json(ContractSchemas.firstExample(objectMapper, PAYMENT_SUCCEEDED_FILE))));
        InboundEvent released = parser.parse(message(MessageTypes.EVENT_STOCK_RELEASED,
                json(ContractSchemas.firstExample(objectMapper, STOCK_RELEASED_FILE))));

        PaymentSucceeded payment = (PaymentSucceeded) succeeded.payload();
        assertThat(payment.amount().amount()).isEqualTo("578000.00");
        assertThat(payment.amount().value()).isEqualByComparingTo("578000.00");
        assertThat(((StockReleased) released.payload()).reason())
                .isEqualTo(StockReleaseReason.PAYMENT_FAILED);
    }

    @Test
    void rejectsMalformedJson() {
        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, "{not valid json"));
    }

    @Test
    void rejectsHeaderBodyTypeMismatch() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_COMMITTED, json(example)));
    }

    @Test
    void rejectsMissingTypeInBothHeaderAndBody() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        example.remove("eventType");

        assertPermanentRejection(message(null, json(example)));
    }

    @Test
    void rejectsUnsupportedEventType() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        example.put("eventType", "inventory.not-a-saga-event");

        assertPermanentRejection(message("inventory.not-a-saga-event", json(example)));
    }

    @Test
    void rejectsMissingRequiredEnvelopeField() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        example.remove("correlationId");

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, json(example)));
    }

    @Test
    void rejectsInvalidEventVersionType() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        example.put("eventVersion", "1");

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, json(example)));
    }

    @Test
    void rejectsInvalidTraceId() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        example.put("traceId", "4BF92F3577B34DA6A3CE929D0E0E4736");

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, json(example)));
    }

    @Test
    void rejectsPayloadOrderIdNotMatchingCorrelationId() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        ((ObjectNode) example.get("payload")).put("orderId", UUID.randomUUID().toString());

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, json(example)));
    }

    @Test
    void rejectsWrongPayloadFieldType() throws IOException {
        ObjectNode example = exampleNode(STOCK_RESERVED_FILE);
        ((ObjectNode) example.get("payload").get("items").get(0)).put("quantity", "two");

        assertPermanentRejection(message(MessageTypes.EVENT_STOCK_RESERVED, json(example)));
    }

    @Test
    void rejectsMoneyWithoutTwoFractionDigits() throws IOException {
        ObjectNode example = exampleNode(PAYMENT_SUCCEEDED_FILE);
        ((ObjectNode) example.get("payload").get("amount")).put("amount", "578000");

        assertPermanentRejection(message(MessageTypes.EVENT_PAYMENT_SUCCEEDED, json(example)));
    }

    @Test
    void rejectsUnsupportedEnumValue() throws IOException {
        ObjectNode example = exampleNode(PAYMENT_FAILED_FILE);
        ((ObjectNode) example.get("payload")).put("failureCode", "PAYMENT_UNKNOWN");

        assertPermanentRejection(message(MessageTypes.EVENT_PAYMENT_FAILED, json(example)));
    }

    private static Stream<Arguments> contractExamples() {
        return Stream.of(
                Arguments.of(MessageTypes.EVENT_STOCK_RESERVED, STOCK_RESERVED_FILE,
                        StockReserved.class),
                Arguments.of(MessageTypes.EVENT_STOCK_RESERVATION_FAILED,
                        RESERVATION_FAILED_FILE, StockReservationFailed.class),
                Arguments.of(MessageTypes.EVENT_STOCK_COMMITTED, STOCK_COMMITTED_FILE,
                        StockCommitted.class),
                Arguments.of(MessageTypes.EVENT_STOCK_COMMIT_FAILED, STOCK_COMMIT_FAILED_FILE,
                        StockCommitFailed.class),
                Arguments.of(MessageTypes.EVENT_STOCK_RELEASED, STOCK_RELEASED_FILE,
                        StockReleased.class),
                Arguments.of(MessageTypes.EVENT_PAYMENT_SUCCEEDED, PAYMENT_SUCCEEDED_FILE,
                        PaymentSucceeded.class),
                Arguments.of(MessageTypes.EVENT_PAYMENT_FAILED, PAYMENT_FAILED_FILE,
                        PaymentFailed.class),
                Arguments.of(MessageTypes.EVENT_PAYMENT_REFUNDED, PAYMENT_REFUNDED_FILE,
                        PaymentRefunded.class));
    }

    private void assertPermanentRejection(Message message) {
        assertThatThrownBy(() -> parser.parse(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private ObjectNode exampleNode(String schemaFile) throws IOException {
        return (ObjectNode) ContractSchemas.firstExample(objectMapper, schemaFile);
    }

    private Message message(String typeHeader, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        if (typeHeader != null) {
            properties.setType(typeHeader);
        }
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
