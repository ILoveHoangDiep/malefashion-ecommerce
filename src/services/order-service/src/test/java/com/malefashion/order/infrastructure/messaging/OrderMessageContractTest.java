package com.malefashion.order.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.saga.StockReleaseReason;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.TestObjectMappers;
import com.malefashion.order.support.TestOrderProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi message factory tạo ra phải validate được bằng JSON Schema sống trong contracts/events.
 */
class OrderMessageContractTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final ObjectMapper MAPPER = TestObjectMappers.contract();

    @ParameterizedTest(name = "{0}")
    @MethodSource("factoryMessages")
    void factoryOutputMatchesContractSchema(String eventType,
                                            MessageEnvelope<?> envelope)
            throws JsonProcessingException {
        assertThat(envelope.eventType()).isEqualTo(eventType);

        String json = MAPPER.writeValueAsString(envelope);

        ContractSchemas.validate(eventType, json);
        assertThat(json).contains("\"eventVersion\":1");
        assertThat(json).contains("\"producer\":\"order-service\"");
        assertThat(json).contains("\"occurredAt\":\"2026-10-22T03:15:30.123Z\"");
    }

    private static Stream<Arguments> factoryMessages() {
        OrderMessageFactory factory = new OrderMessageFactory(
                TestOrderProperties.valid(true),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Order order = OrderFixtures.newOrder(NOW);
        Order refundedOrder = OrderFixtures.newOrder(NOW);
        refundedOrder.applyPayment(OrderFixtures.PAYMENT_ID, OrderFixtures.TOTAL,
                OrderFixtures.PROVIDER_TRANSACTION_ID, NOW);
        UUID eventId = UUID.randomUUID();

        return Stream.of(
                Arguments.of(MessageTypes.EVENT_ORDER_CREATED, factory.orderCreated(order)),
                Arguments.of(MessageTypes.EVENT_ORDER_CONFIRMED, factory.orderConfirmed(order)),
                Arguments.of(MessageTypes.EVENT_ORDER_CANCELLED,
                        factory.orderCancelled(order, CancelReason.OUT_OF_STOCK)),
                Arguments.of(MessageTypes.COMMAND_RESERVE_STOCK,
                        factory.reserveStock(eventId, order)),
                Arguments.of(MessageTypes.COMMAND_COMMIT_STOCK,
                        factory.commitStock(eventId, order.getId(),
                                OrderFixtures.RESERVATION_ID)),
                Arguments.of(MessageTypes.COMMAND_RELEASE_STOCK,
                        factory.releaseStock(eventId, order.getId(),
                                OrderFixtures.RESERVATION_ID, StockReleaseReason.PAYMENT_FAILED)),
                Arguments.of(MessageTypes.COMMAND_PROCESS_PAYMENT,
                        factory.processPayment(eventId, order)),
                Arguments.of(MessageTypes.COMMAND_REFUND_PAYMENT,
                        factory.refundPayment(eventId, refundedOrder)));
    }
}
