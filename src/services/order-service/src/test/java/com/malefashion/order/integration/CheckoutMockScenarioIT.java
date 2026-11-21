package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.application.InboxProcessor;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.InboundEventParser;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.8: mockPaymentScenario chỉ dùng được khi bật cấu hình demo/local; giá trị được lưu trên order
 * và chảy vào command process-payment của saga.
 */
@TestPropertySource(properties = "order.mock-scenario-enabled=true")
class CheckoutMockScenarioIT extends WebIntegrationTestBase {

    @Autowired
    private InboxProcessor inboxProcessor;

    @Autowired
    private InboundEventParser inboundEventParser;

    @Test
    void persistedScenarioIsUsedByProcessPaymentCommand() throws Exception {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();

        ResponseEntity<String> response = checkout(
                UpstreamFixtures.checkoutBodyWithScenario("FAILURE"), UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orderId = UUID.fromString(json(response).path("orderId").asText());
        Order order = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(order.getMockPaymentScenario()).isEqualTo("FAILURE");

        deliver("inventory-stock-reserved.json", orderId);

        Order updated = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(updated.getSagaStep()).isEqualTo(SagaStep.AWAITING_PAYMENT);

        List<OutboxEvent> payments = outboxEventRepository.findAll().stream()
                .filter(event -> MessageTypes.COMMAND_PROCESS_PAYMENT
                        .equals(event.getEventType()))
                .toList();
        assertThat(payments).hasSize(1);
        JsonNode payload = payments.getFirst().getPayload().path("payload");
        assertThat(payload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.path("mockScenario").asText()).isEqualTo("FAILURE");
        assertThat(payload.path("idempotencyKey").asText()).isEqualTo(orderId.toString());
        ContractSchemas.validate(payments.getFirst().getEventType(),
                objectMapper.writeValueAsString(payments.getFirst().getPayload()));
    }

    private void deliver(String schemaFile, UUID orderId) throws Exception {
        JsonNode envelope = ContractSchemas.firstExample(objectMapper, schemaFile);
        UUID eventId = UUID.randomUUID();
        ((ObjectNode) envelope).put("eventId", eventId.toString());
        ((ObjectNode) envelope).put("correlationId", orderId.toString());
        ((ObjectNode) envelope.get("payload")).put("orderId", orderId.toString());

        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        properties.setType(envelope.get("eventType").asText());
        Message message = new Message(objectMapper.writeValueAsBytes(envelope), properties);
        InboundEvent event = inboundEventParser.parse(message);
        inboxProcessor.process(event);
    }
}
