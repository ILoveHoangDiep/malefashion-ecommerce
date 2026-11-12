package com.malefashion.payment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.MessagingTopology;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.payment.infrastructure.outbox.OutboxPublisher;
import com.malefashion.payment.infrastructure.persistence.OutboxEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thêm RabbitMQ thật (Testcontainers) và nạp deploy/rabbitmq/definitions.json qua Management API,
 * giống hạ tầng của inventory-service. PostgreSQL dùng chung container của {@link IntegrationTestBase}.
 */
public abstract class MessagingIntegrationTestBase extends IntegrationTestBase {

    protected static final String CONTRACT_INSTANT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

    protected static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3-management-alpine");

    private static final Map<String, String> COMMAND_SCHEMA_FILES = Map.of(
            MessageTypes.COMMAND_PROCESS_PAYMENT, "payment-process-payment.json",
            MessageTypes.COMMAND_REFUND_PAYMENT, "payment-refund-payment.json"
    );

    static {
        RABBIT.start();
        importRabbitDefinitions();
    }

    @DynamicPropertySource
    static void registerRabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");

        // Test tự gọi publisher để thời điểm publish là xác định.
        registry.add("payment.outbox.publish-interval-ms", () -> "3600000");
    }

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @BeforeEach
    void resetMessagingState() {
        purgeQueue(MessagingTopology.PAYMENT_COMMANDS_QUEUE);
        purgeQueue(MessagingTopology.PAYMENT_COMMANDS_DLQ);
        purgeQueue(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
    }

    protected UUID publishCommand(String commandType, UUID eventId, UUID orderId, Object payload) {
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(
                eventId, commandType, 1, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "order-service", orderId, TRACE_ID, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize command", failure);
        }
        String schemaFile = COMMAND_SCHEMA_FILES.get(commandType);
        if (schemaFile != null) {
            ContractSchemas.validate(schemaFile, json);
        }
        sendRawCommand(commandType, commandType, eventId, json);
        return eventId;
    }

    protected void sendRawCommand(String commandType, UUID messageId, String json) {
        sendRawCommand(commandType, commandType, messageId, json);
    }

    /**
     * Cho phép routing key và AMQP {@code type} header khác nhau để test các trường hợp lệch/không hỗ trợ.
     */
    protected void sendRawCommand(String routingKey, String typeHeader, UUID messageId, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(typeHeader);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MessagingTopology.COMMANDS_EXCHANGE, routingKey,
                new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    protected OutboxEvent awaitOutboxEvent(String eventType, UUID orderId) {
        return awaitOutboxEvents(eventType, orderId, 1).getFirst();
    }

    protected List<OutboxEvent> awaitOutboxEvents(String eventType, UUID orderId, int count) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> outboxEvents(eventType, orderId).size() >= count);
        return outboxEvents(eventType, orderId);
    }

    protected List<OutboxEvent> outboxEvents(String eventType, UUID orderId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .filter(event -> orderId.toString().equals(
                        event.getPayload().path("payload").path("orderId").asText()))
                .toList();
    }

    /**
     * Chờ service lưu event vào outbox, publish pending rồi nhận message broker giao cho saga queue.
     */
    protected Message awaitPublishedEvent(String eventType, UUID orderId) {
        awaitOutboxEvent(eventType, orderId);
        outboxPublisher.publishPendingEvents();
        return receiveEvent(eventType, orderId, Duration.ofSeconds(20));
    }

    protected Message receiveEvent(String eventType, UUID orderId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE, 500);
            if (message == null) {
                continue;
            }
            JsonNode envelope = parse(message);
            if (eventType.equals(envelope.path("eventType").asText())
                    && orderId.toString().equals(envelope.path("payload").path("orderId").asText())) {
                ContractSchemas.validateEvent(eventType, bodyOf(message));
                return message;
            }
        }
        throw new AssertionError("No " + eventType + " event for order " + orderId
                + " within " + timeout);
    }

    protected void assertNoEvent(String eventType, UUID orderId, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE, 200);
            if (message == null) {
                continue;
            }
            JsonNode envelope = parse(message);
            boolean matches = eventType.equals(envelope.path("eventType").asText())
                    && orderId.toString().equals(envelope.path("payload").path("orderId").asText());
            assertThat(matches)
                    .as("Unexpected %s event for order %s", eventType, orderId)
                    .isFalse();
        }
    }

    protected JsonNode envelopeOf(Message message) {
        return parse(message);
    }

    protected String bodyOf(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    protected long processedMessageCount(UUID messageId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Long.class, messageId);
        return count == null ? 0 : count;
    }

    protected void awaitQueueEmpty(String queue) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(queue) == 0);
    }

    protected long messageCount(String queue) {
        Integer count = rabbitTemplate.execute(
                channel -> channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0 : count;
    }

    protected void assertContractInstant(String value) {
        assertThat(value).matches(CONTRACT_INSTANT_PATTERN);
    }

    protected void purgeQueue(String queue) {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(queue);
            return null;
        });
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException failure) {
            throw new AssertionError("Broker message is not JSON: " + bodyOf(message), failure);
        }
    }

    private static void importRabbitDefinitions() {
        Path definitions = ContractSchemas.eventsDirectory()
                .getParent()
                .getParent()
                .resolve("deploy/rabbitmq/definitions.json");
        String body;
        try {
            body = Files.readString(definitions);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + definitions, failure);
        }

        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        // Management server đóng kết nối h2c-upgrade; ép plain HTTP/1.1.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        URI endpoint = URI.create(RABBIT.getHttpUrl() + "/api/definitions");

        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return;
                }
                lastFailure = new IllegalStateException(
                        "RabbitMQ definitions import returned " + response.statusCode()
                                + ": " + response.body());
            } catch (Exception failure) {
                lastFailure = failure;
            }
            sleepOneSecond();
        }
        throw new IllegalStateException("Could not import " + definitions + " into RabbitMQ",
                lastFailure);
    }

    private static void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
