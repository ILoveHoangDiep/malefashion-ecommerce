package com.malefashion.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.inventory.application.ReservationExpiryService;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.MessagingTopology;
import com.malefashion.inventory.infrastructure.messaging.command.CommitStockPayload;
import com.malefashion.inventory.infrastructure.messaging.command.ReleaseReason;
import com.malefashion.inventory.infrastructure.messaging.command.ReleaseStockPayload;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.OutboxStatus;
import com.malefashion.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end scenarios: commands go through the real broker into the listener, the outbox publisher
 * sends the result to {@code malefashion.events}, and every received message is validated against the
 * JSON Schema in {@code contracts/events}.
 */
class InventoryCommandFlowIntegrationTest extends IntegrationTestBase {

    private static final String TEE_SKU = "MF-TEE-BLACK-M";
    private static final String COMMANDS_DLQ = MessagingTopology.INVENTORY_COMMANDS_QUEUE + ".dlq";

    @Autowired
    private ReservationExpiryService reservationExpiryService;

    @Test
    void topologyComesFromTheRepositoryDefinitionsFile() throws Exception {
        JsonNode queue = managementGet("/api/queues/%2F/" + MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(queue.path("name").asText()).isEqualTo(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").asText())
                .isEqualTo("malefashion.dlx");
        assertThat(queue.path("arguments").path("x-dead-letter-routing-key").asText())
                .isEqualTo("inventory-service.commands.dlq");

        JsonNode exchange = managementGet("/api/exchanges/%2F/" + MessagingTopology.EVENTS_EXCHANGE);
        assertThat(exchange.path("type").asText()).isEqualTo("topic");

        JsonNode bindings = managementGet(
                "/api/bindings/%2F/e/" + MessagingTopology.EVENTS_EXCHANGE
                        + "/q/" + MessagingTopology.ORDER_SAGA_EVENTS_QUEUE);
        Set<String> routingKeys = bindings.valueStream()
                .map(binding -> binding.path("routing_key").asText())
                .collect(Collectors.toSet());
        assertThat(routingKeys).contains(
                MessageTypes.EVENT_STOCK_RESERVED,
                MessageTypes.EVENT_STOCK_RESERVATION_FAILED,
                MessageTypes.EVENT_STOCK_COMMITTED,
                MessageTypes.EVENT_STOCK_COMMIT_FAILED,
                MessageTypes.EVENT_STOCK_RELEASED);

        JsonNode commandBindings = managementGet(
                "/api/bindings/%2F/e/" + MessagingTopology.COMMANDS_EXCHANGE
                        + "/q/" + MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        Set<String> commandKeys = commandBindings.valueStream()
                .map(binding -> binding.path("routing_key").asText())
                .collect(Collectors.toSet());
        assertThat(commandKeys).containsExactlyInAnyOrder(
                MessageTypes.COMMAND_RESERVE_STOCK,
                MessageTypes.COMMAND_COMMIT_STOCK,
                MessageTypes.COMMAND_RELEASE_STOCK);
    }

    @Test
    void reserveSuccessDeductsStockAndPublishesContractValidEventWithAmqpProperties() {
        UUID variantId = seedStock(TEE_SKU, 10);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 3);
        Message message = awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId);
        JsonNode envelope = envelopeOf(message);

        assertThat(envelope.path("producer").asText()).isEqualTo("inventory-service");
        assertThat(envelope.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.path("correlationId").asText()).isEqualTo(orderId.toString());
        assertThat(envelope.path("traceId").asText()).isEqualTo(TRACE_ID);

        JsonNode payload = envelope.path("payload");
        UUID reservationId = UUID.fromString(payload.path("reservationId").asText());
        assertThat(payload.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.path("items")).hasSize(1);
        assertThat(payload.path("items").get(0).path("variantId").asText())
                .isEqualTo(variantId.toString());
        assertThat(payload.path("items").get(0).path("sku").asText()).isEqualTo(TEE_SKU);
        assertThat(payload.path("items").get(0).path("quantity").asInt()).isEqualTo(3);

        Instant expiresAt = Instant.parse(payload.path("expiresAt").asText());
        assertThat(expiresAt).isBetween(
                Instant.now().plus(14, ChronoUnit.MINUTES),
                Instant.now().plus(16, ChronoUnit.MINUTES));

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(7);
        assertThat(stock.getReservedQuantity()).isEqualTo(3);

        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(reservation.getId()).isEqualTo(reservationId);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getExpiresAt()).isEqualTo(expiresAt);

        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(envelope.path("eventId").asText());
        assertThat(properties.getType()).isEqualTo(MessageTypes.EVENT_STOCK_RESERVED);
        assertThat(properties.getCorrelationId()).isEqualTo(orderId.toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((Object) properties.getHeader("x-event-version")).isEqualTo(1);

        UUID outboxId = UUID.fromString(envelope.path("eventId").asText());
        OutboxEvent stored = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(stored.getPublishedAt()).isNotNull();
    }

    @Test
    void insufficientStockEmitsFailureAndLeavesStockUntouched() {
        UUID variantId = seedStock(TEE_SKU, 1);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        Message message = awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVATION_FAILED, orderId);
        JsonNode payload = envelopeOf(message).path("payload");

        assertThat(payload.path("reason").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(payload.path("failedItems")).hasSize(1);
        assertThat(payload.path("failedItems").get(0).path("variantId").asText())
                .isEqualTo(variantId.toString());
        assertThat(payload.path("failedItems").get(0).path("sku").asText()).isEqualTo(TEE_SKU);
        assertThat(payload.path("failedItems").get(0).path("requestedQuantity").asInt()).isEqualTo(2);
        assertThat(payload.path("failedItems").get(0).path("availableQuantity").asInt()).isEqualTo(1);

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(1);
        assertThat(stock.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void unknownVariantEmitsVariantNotFoundWithCommandSku() {
        UUID unknownVariant = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String commandSku = "MF-HOODIE-RED-L";

        publishReserve(UUID.randomUUID(), orderId, unknownVariant, commandSku, 1);
        Message message = awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVATION_FAILED, orderId);
        JsonNode payload = envelopeOf(message).path("payload");

        assertThat(payload.path("reason").asText()).isEqualTo("VARIANT_NOT_FOUND");
        assertThat(payload.path("failedItems")).hasSize(1);
        assertThat(payload.path("failedItems").get(0).path("variantId").asText())
                .isEqualTo(unknownVariant.toString());
        assertThat(payload.path("failedItems").get(0).path("sku").asText()).isEqualTo(commandSku);
        assertThat(payload.path("failedItems").get(0).path("requestedQuantity").asInt()).isEqualTo(1);
        assertThat(payload.path("failedItems").get(0).path("availableQuantity").asInt()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
        assertThat(stockItemRepository.count()).isZero();
    }

    @Test
    void duplicateCommandWithSameEventIdDeductsStockOnlyOnce() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publishReserve(eventId, orderId, variantId, TEE_SKU, 2);
        publishReserve(eventId, orderId, variantId, TEE_SKU, 2);
        awaitOutboxEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId);
        awaitQueueEmpty(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId)).hasSize(1));

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(processedMessageCount(eventId)).isEqualTo(1);
        assertThat(processedMessageCountByConsumer()).isEqualTo(1);
    }

    @Test
    void repeatedReserveForSameOrderReplaysSameReservation() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode first = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");
        String firstReservationId = first.path("reservationId").asText();
        String firstExpiresAt = first.path("expiresAt").asText();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode replay = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");

        assertThat(replay.path("reservationId").asText()).isEqualTo(firstReservationId);
        assertThat(replay.path("expiresAt").asText()).isEqualTo(firstExpiresAt);
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId)).hasSize(2);

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    void commitSuccessClearsReservedStockAndEmitsStockCommitted() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode reserved = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");
        UUID reservationId = UUID.fromString(reserved.path("reservationId").asText());

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), orderId,
                new CommitStockPayload(orderId, reservationId));
        JsonNode committed = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMITTED, orderId))
                .path("payload");

        assertThat(committed.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(committed.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(Instant.parse(committed.path("committedAt").asText())).isNotNull();

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    void commitAfterExpiryEmitsCommitFailedWithoutTouchingStock() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode reserved = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");
        UUID reservationId = UUID.fromString(reserved.path("reservationId").asText());
        backdateExpiry(reservationId);

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), orderId,
                new CommitStockPayload(orderId, reservationId));
        JsonNode failed = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMIT_FAILED, orderId))
                .path("payload");

        assertThat(failed.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(failed.path("reason").asText()).isEqualTo("RESERVATION_EXPIRED");

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void commitForUnknownReservationEmitsCommitFailedWithCommandReservationId() {
        UUID orderId = UUID.randomUUID();
        UUID unknownReservationId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), orderId,
                new CommitStockPayload(orderId, unknownReservationId));
        JsonNode failed = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMIT_FAILED, orderId))
                .path("payload");

        assertThat(failed.path("reservationId").asText()).isEqualTo(unknownReservationId.toString());
        assertThat(failed.path("reason").asText()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void releaseReturnsStockAndDuplicateReleaseIsIgnored() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode reserved = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");
        UUID reservationId = UUID.fromString(reserved.path("reservationId").asText());

        ReleaseStockPayload release = new ReleaseStockPayload(orderId, reservationId, ReleaseReason.PAYMENT_FAILED);
        publishCommand(MessageTypes.COMMAND_RELEASE_STOCK, UUID.randomUUID(), orderId, release);
        JsonNode released = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RELEASED, orderId))
                .path("payload");

        assertThat(released.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(released.path("reason").asText()).isEqualTo("PAYMENT_FAILED");

        StockItem afterRelease = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(afterRelease.getAvailableQuantity()).isEqualTo(5);
        assertThat(afterRelease.getReservedQuantity()).isZero();

        UUID duplicateEventId = UUID.randomUUID();
        publishCommand(MessageTypes.COMMAND_RELEASE_STOCK, duplicateEventId, orderId, release);
        awaitQueueEmpty(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RELEASED, orderId)).hasSize(1));

        StockItem afterDuplicate = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(afterDuplicate.getAvailableQuantity()).isEqualTo(5);
        assertThat(afterDuplicate.getReservedQuantity()).isZero();
        assertThat(processedMessageCount(duplicateEventId)).isEqualTo(1);
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void expiryJobReturnsStockAndEmitsStockReleasedOnce() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        UUID reservationId = UUID.fromString(
                envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                        .path("payload").path("reservationId").asText());
        backdateExpiry(reservationId);

        reservationExpiryService.expireDueReservations();

        JsonNode released = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RELEASED, orderId))
                .path("payload");
        assertThat(released.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(released.path("reason").asText()).isEqualTo("RESERVATION_EXPIRED");

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(5);
        assertThat(stock.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);

        List<OutboxEvent> before = outboxEvents(MessageTypes.EVENT_STOCK_RELEASED, orderId);
        reservationExpiryService.expireDueReservations();
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RELEASED, orderId)).hasSize(before.size());
    }

    @Test
    void amqpMessageIdMatchesEnvelopeEventIdAndOutboxRowId() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 1);
        Message message = awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId);
        JsonNode envelope = envelopeOf(message);

        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(outboxEventRepository.findById(eventId)).isPresent();
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId)).hasSize(1);
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId).getFirst().getId())
                .isEqualTo(eventId);
    }

    @Test
    void repeatedCommitOnCommittedReservationReplaysStockCommitted() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), orderId, variantId, TEE_SKU, 2);
        JsonNode reserved = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId))
                .path("payload");
        UUID reservationId = UUID.fromString(reserved.path("reservationId").asText());

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), orderId,
                new CommitStockPayload(orderId, reservationId));
        JsonNode firstCommit = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMITTED, orderId))
                .path("payload");

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), orderId,
                new CommitStockPayload(orderId, reservationId));
        JsonNode replay = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMITTED, orderId))
                .path("payload");

        assertThat(replay.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(replay.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(replay.path("committedAt").asText())
                .isEqualTo(firstCommit.path("committedAt").asText());
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_COMMITTED, orderId)).hasSize(2);

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    void releaseForUnknownReservationEmitsNoEventAndDoesNotDeadLetter() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publishCommand(MessageTypes.COMMAND_RELEASE_STOCK, eventId, orderId,
                new ReleaseStockPayload(orderId, UUID.randomUUID(), ReleaseReason.PAYMENT_FAILED));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> processedMessageCount(eventId) == 1);
        awaitQueueEmpty(MessagingTopology.INVENTORY_COMMANDS_QUEUE);

        assertThat(outboxEventRepository.count()).isZero();
        assertNoEvent(MessageTypes.EVENT_STOCK_RELEASED, orderId, Duration.ofSeconds(1));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(messageCount(COMMANDS_DLQ)).isZero());
    }

    @Test
    void commitWithMismatchedOrderEmitsCommitFailedWithCommandReservationId() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID reservationOrderId = UUID.randomUUID();
        UUID commandOrderId = UUID.randomUUID();

        publishReserve(UUID.randomUUID(), reservationOrderId, variantId, TEE_SKU, 2);
        UUID reservationId = UUID.fromString(
                envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, reservationOrderId))
                        .path("payload").path("reservationId").asText());

        publishCommand(MessageTypes.COMMAND_COMMIT_STOCK, UUID.randomUUID(), commandOrderId,
                new CommitStockPayload(commandOrderId, reservationId));
        JsonNode failed = envelopeOf(awaitPublishedEvent(MessageTypes.EVENT_STOCK_COMMIT_FAILED, commandOrderId))
                .path("payload");

        assertThat(failed.path("orderId").asText()).isEqualTo(commandOrderId.toString());
        assertThat(failed.path("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(failed.path("reason").asText()).isEqualTo("RESERVATION_EXPIRED");

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(messageCount(COMMANDS_DLQ)).isZero();
    }

    private UUID seedStock(String sku, int quantity) {
        UUID variantId = UUID.randomUUID();
        stockItemRepository.save(StockItem.create(variantId, sku, quantity, Instant.now()));
        return variantId;
    }

    private void publishReserve(UUID eventId, UUID orderId, UUID variantId, String sku, int quantity) {
        publishCommand(MessageTypes.COMMAND_RESERVE_STOCK, eventId, orderId,
                new ReserveStockPayload(orderId,
                        List.of(new ReserveStockPayload.Item(variantId, sku, quantity))));
    }

    private void backdateExpiry(UUID reservationId) {
        jdbcTemplate.update(
                "UPDATE stock_reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
    }

    private long processedMessageCountByConsumer() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE consumer = 'inventory-service'", Long.class);
        return count == null ? 0 : count;
    }

    private JsonNode managementGet(String path) throws Exception {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", authorization)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }
}
