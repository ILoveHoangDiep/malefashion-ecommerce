package com.malefashion.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.application.InboxProcessor;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.TestObjectMappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderSagaEventListenerTest {

    private final ObjectMapper objectMapper = TestObjectMappers.contract();
    private final InboxProcessor inboxProcessor = mock(InboxProcessor.class);
    private final OrderSagaEventListener listener = new OrderSagaEventListener(
            new InboundEventParser(objectMapper), inboxProcessor);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsTraceOrderAndEventIdInMdcAndClearsAfterSuccess() throws IOException {
        JsonNode example = ContractSchemas.firstExample(objectMapper,
                "inventory-stock-reserved.json");

        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(MDC.get("orderId"))
                    .isEqualTo("5d1c9b2a-8e4f-4a6b-b1c2-3d4e5f6a7b8c");
            assertThat(MDC.get("eventId"))
                    .isEqualTo("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
            assertThat(MDC.getCopyOfContextMap()).doesNotContainKeys("userEmail", "payload");
            return null;
        }).when(inboxProcessor).process(any());

        listener.onEvent(message(json(example)));

        verify(inboxProcessor).process(any());
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void clearsMdcWhenHandlerThrows() throws IOException {
        JsonNode example = ContractSchemas.firstExample(objectMapper,
                "inventory-stock-reserved.json");
        doThrow(new IllegalStateException("boom")).when(inboxProcessor).process(any());

        assertThatThrownBy(() -> listener.onEvent(message(json(example))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void parseFailureNeverCallsProcessor() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setType(MessageTypes.EVENT_STOCK_RESERVED);
        Message malformed = new Message("{bad".getBytes(StandardCharsets.UTF_8), properties);

        assertThatThrownBy(() -> listener.onEvent(malformed))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        verify(inboxProcessor, never()).process(any());
    }

    private Message message(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setType(MessageTypes.EVENT_STOCK_RESERVED);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String json(JsonNode node) throws IOException {
        return objectMapper.writeValueAsString(node);
    }
}
