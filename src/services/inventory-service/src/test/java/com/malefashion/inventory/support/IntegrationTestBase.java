package com.malefashion.inventory.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.inventory.infrastructure.messaging.MessagingTopology;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.outbox.OutboxPublisher;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.inventory.infrastructure.persistence.ProcessedMessageRepository;
import com.malefashion.inventory.infrastructure.persistence.ReservationRepository;
import com.malefashion.inventory.infrastructure.persistence.StockItemRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real PostgreSQL 16 and RabbitMQ containers once, imports the repository's RabbitMQ
 * definitions into the broker, and gives the tests helpers to publish commands and receive events.
 */
@SpringBootTest
// Every test class gets a fresh context, so a cached context can never leave a second listener
// container consuming from the same queue while another class is running.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    protected static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("inventory_db")
                    .withUsername("postgres")
                    .withPassword("postgrespassword");

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3-management-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
        importRabbitDefinitions();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");

        // Tests call the jobs manually; keep the schedulers out of the way.
        registry.add("inventory.outbox.initial-delay-ms", () -> "3600000");
        registry.add("inventory.outbox.publish-interval-ms", () -> "3600000");
        registry.add("inventory.reservation.expiry-initial-delay-ms", () -> "3600000");
        registry.add("inventory.reservation.expiry-scan-interval-ms", () -> "3600000");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected StockItemRepository stockItemRepository;

    @Autowired
    protected ReservationRepository reservationRepository;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected ProcessedMessageRepository processedMessageRepository;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE stock_reservation_items, stock_reservations, stock_items,
                               outbox_events, processed_messages
                """);
        purgeQueue(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        purgeQueue(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
        purgeQueue(MessagingTopology.INVENTORY_COMMANDS_QUEUE + ".dlq");
    }

    @AfterEach
    void everyEmittedEventMatchesItsContractSchema() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        for (OutboxEvent event : events) {
            ContractSchemas.validateEvent(event.getEventType(), event.getPayload().toString());
        }
    }

    protected UUID publishCommand(String commandType, UUID eventId, UUID orderId, Object payload) {
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(
                eventId, commandType, 1, Instant.now(), "order-service", orderId, TRACE_ID, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize command", failure);
        }
        ContractSchemas.validateCommand(commandType, json);
        sendRawCommand(commandType, eventId, json);
        return eventId;
    }

    protected void sendRawCommand(String commandType, UUID messageId, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(commandType);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MessagingTopology.COMMANDS_EXCHANGE, commandType,
                new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    protected OutboxEvent awaitOutboxEvent(String eventType, UUID orderId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !outboxEvents(eventType, orderId).isEmpty());
        return outboxEvents(eventType, orderId).getFirst();
    }

    protected List<OutboxEvent> outboxEvents(String eventType, UUID orderId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .filter(event -> orderId.toString().equals(
                        event.getPayload().path("payload").path("orderId").asText()))
                .toList();
    }

    /**
     * Waits until the service stored the event, publishes pending outbox rows and returns the message
     * the broker delivered to the saga queue.
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
        Integer count = rabbitTemplate.execute(channel -> channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0 : count;
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException failure) {
            throw new AssertionError("Broker message is not JSON: " + bodyOf(message), failure);
        }
    }

    private void purgeQueue(String queue) {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(queue);
            return null;
        });
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
        // RabbitMQ's management server closes h2c-upgrade connections; force plain HTTP/1.1.
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
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return;
                }
                lastFailure = new IllegalStateException(
                        "RabbitMQ definitions import returned " + response.statusCode() + ": " + response.body());
            } catch (Exception failure) {
                lastFailure = failure;
            }
            sleepOneSecond();
        }
        throw new IllegalStateException("Could not import " + definitions + " into RabbitMQ", lastFailure);
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
