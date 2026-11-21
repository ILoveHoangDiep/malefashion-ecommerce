package com.malefashion.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.order.infrastructure.messaging.MessagingTopology;
import com.malefashion.order.infrastructure.outbox.OutboxPublisher;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ContextConfiguration;
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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * RabbitMQ thật + definitions.json nạp qua Management API. Kế thừa PostgreSQL container của
 * {@link IntegrationTestBase}; initializer bật lại listener + outbox publisher vì lớp cha cố tình
 * tắt chúng cho test chỉ có DB.
 */
@ContextConfiguration(initializers = MessagingIntegrationTestBase.MessagingIsolationInitializer.class)
public abstract class MessagingIntegrationTestBase extends IntegrationTestBase {

    protected static final String CONTRACT_INSTANT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3-management-alpine");

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

        // Test gọi publisher thủ công để thời điểm publish xác định.
        registry.add("order.outbox.publish-interval-ms", () -> "3600000");
    }

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void resetMessagingState() {
        purgeQueue(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
        purgeQueue(MessagingTopology.ORDER_SAGA_EVENTS_DLQ);
        purgeQueue(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        purgeQueue(MessagingTopology.INVENTORY_COMMANDS_DLQ);
        purgeQueue(MessagingTopology.NOTIFICATION_EVENTS_QUEUE);
        purgeQueue(MessagingTopology.NOTIFICATION_EVENTS_DLQ);
    }

    protected void stopListeners() {
        listenerRegistry.getListenerContainers().forEach(container -> container.stop());
    }

    protected void sendRaw(String routingKey, String typeHeader, UUID messageId, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setType(typeHeader);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, routingKey,
                new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    protected void sendRawEvent(String eventType, UUID eventId, String json) {
        sendRaw(eventType, eventType, eventId, json);
    }

    protected ObjectNode envelopeNode(UUID eventId, String eventType, UUID orderId,
                                      Object payload) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", eventType);
        root.put("eventVersion", 1);
        root.put("occurredAt", "2026-10-22T03:15:30.123Z");
        root.put("producer", "inventory-service");
        root.put("correlationId", orderId.toString());
        root.put("traceId", TestOrderProperties.TRACE_ID);
        root.set("payload", objectMapper.valueToTree(payload));
        return root;
    }

    protected String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize test message", failure);
        }
    }

    protected JsonNode firstExample(String schemaFile) throws IOException {
        return ContractSchemas.firstExample(objectMapper, schemaFile);
    }

    protected long messageCount(String queue) {
        Integer count = rabbitTemplate.execute(
                channel -> channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0 : count;
    }

    protected long processedMessageCount(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?", Long.class,
                eventId);
        return count == null ? 0 : count;
    }

    protected long processedMessageCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages", Long.class);
        return count == null ? 0 : count;
    }

    protected void awaitProcessed(UUID eventId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> processedMessageCount(eventId) == 1);
    }

    protected void awaitDeadLettered(String dlq) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(dlq) == 1);
        awaitQueueEmpty(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
    }

    protected void assertNoFurtherDelivery(String dlq) {
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(2))
                .until(() -> messageCount(dlq) == 1);
        assertQueueEmpty(MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
    }

    protected void awaitQueueEmpty(String queue) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(queue) == 0);
    }

    protected void assertQueueEmpty(String queue) {
        if (messageCount(queue) != 0) {
            throw new AssertionError("Queue " + queue + " is not empty");
        }
    }

    protected void purgeQueue(String queue) {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(queue);
            return null;
        });
    }

    protected OutboxEvent awaitOutboxEvent(UUID eventId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> outboxEventRepository.findAll().stream()
                        .anyMatch(event -> eventId.equals(event.getEventId())));
        return outboxEventRepository.findAll().stream()
                .filter(event -> eventId.equals(event.getEventId()))
                .findFirst()
                .orElseThrow();
    }

    protected Instant parseContractInstant(String value) {
        return Instant.parse(value);
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

    /**
     * Lớp cha đăng ký {@code auto-startup=false} / {@code publisher.enabled=false} bằng dynamic
     * property chạy sau lớp con. Initializer chạy sau khi mọi dynamic property đã đăng ký; nếu
     * context có {@code spring.rabbitmq.port} động (chỉ messaging test) thì bật lại hai công tắc.
     */
    static final class MessagingIsolationInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final String LISTENER_AUTO_STARTUP =
                "order.messaging.listener.auto-startup";
        private static final String OUTBOX_PUBLISHER_ENABLED =
                "order.outbox.publisher.enabled";

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment environment = context.getEnvironment();
            boolean messagingTest = environment.getPropertySources().stream()
                    .filter(MapPropertySource.class::isInstance)
                    .map(MapPropertySource.class::cast)
                    // DynamicValuesPropertySource lưu Supplier làm giá trị.
                    .anyMatch(MessagingIsolationInitializer::containsDynamicRabbitPort);
            if (!messagingTest) {
                return;
            }
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "orderMessagingTestOverrides",
                    Map.of(LISTENER_AUTO_STARTUP, "true", OUTBOX_PUBLISHER_ENABLED, "true")));
        }

        private static boolean containsDynamicRabbitPort(MapPropertySource source) {
            Map<String, Object> values = source.getSource();
            if (values == null) {
                return false;
            }
            Object value = values.get("spring.rabbitmq.port");
            return value instanceof Supplier<?>;
        }
    }
}
