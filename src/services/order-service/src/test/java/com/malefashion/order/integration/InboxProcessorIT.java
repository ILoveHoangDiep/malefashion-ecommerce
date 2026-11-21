package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.application.InboxProcessor;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.InboundEventParser;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.support.ContractSchemas;
import com.malefashion.order.support.IntegrationTestBase;
import com.malefashion.order.support.RecordingSagaEventHandler;
import com.malefashion.order.support.TestObjectMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dedupe + handler trong một transaction: marker và thay đổi nghiệp vụ cùng commit hoặc rollback.
 * Handler thật thuộc 2b; test dùng handler ghi nhận.
 */
@Import(InboxProcessorIT.HandlerConfig.class)
class InboxProcessorIT extends IntegrationTestBase {

    @Autowired
    private InboxProcessor inboxProcessor;

    @Autowired
    private InboundEventParser inboundEventParser;

    @Autowired
    private RecordingSagaEventHandler recordingHandler;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            TestObjectMappers.contract();

    @BeforeEach
    void resetHandler() {
        recordingHandler.reset();
    }

    @Test
    void processesOnceAndCommitsMarkerWithHandler() throws IOException {
        InboundEvent event = stockReservedEvent();

        inboxProcessor.process(event);
        inboxProcessor.process(event);

        assertThat(recordingHandler.attempts()).isEqualTo(1);
        assertThat(recordingHandler.lastHandled()).get()
                .extracting(RecordingSagaEventHandler.Handled::eventId)
                .isEqualTo(event.eventId());
        assertThat(markerCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void handlerRunsInSameTransactionAndSeesUncommittedMarker() throws IOException {
        InboundEvent event = stockReservedEvent();
        recordingHandler.beforeHandle(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Integer visible = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM processed_messages
                    WHERE consumer = 'order-service' AND message_id = ?
                    """, Integer.class, event.eventId());
            assertThat(visible).isEqualTo(1);
        });

        inboxProcessor.process(event);

        assertThat(recordingHandler.attempts()).isEqualTo(1);
        assertThat(markerCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void handlerFailureRollsBackMarker() throws IOException {
        InboundEvent event = stockReservedEvent();
        recordingHandler.failWith(
                new DataAccessResourceFailureException("simulated database outage"));

        assertThatThrownBy(() -> inboxProcessor.process(event))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(recordingHandler.attempts()).isEqualTo(1);
        assertThat(markerCount(event.eventId())).isZero();
    }

    @Test
    void existingMarkerSkipsHandler() throws IOException {
        InboundEvent event = stockReservedEvent();
        insertMarker(event.eventId());

        inboxProcessor.process(event);

        assertThat(recordingHandler.attempts()).isZero();
        assertThat(markerCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void markerUsesConfiguredConsumerName() throws IOException {
        InboundEvent event = stockReservedEvent();

        inboxProcessor.process(event);

        String consumer = jdbcTemplate.queryForObject("""
                SELECT consumer FROM processed_messages WHERE message_id = ?
                """, String.class, event.eventId());
        assertThat(consumer).isEqualTo("order-service");
    }

    private void insertMarker(UUID eventId) {
        jdbcTemplate.update("""
                INSERT INTO processed_messages (consumer, message_id, processed_at)
                VALUES ('order-service', ?, now())
                """, eventId);
    }

    private long markerCount(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Long.class,
                eventId);
        return count == null ? 0 : count;
    }

    private InboundEvent stockReservedEvent() throws IOException {
        JsonNode example = ContractSchemas.firstExample(objectMapper,
                "inventory-stock-reserved.json");
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setType(MessageTypes.EVENT_STOCK_RESERVED);
        Message message = new Message(objectMapper.writeValueAsBytes(example), properties);
        return inboundEventParser.parse(message);
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
