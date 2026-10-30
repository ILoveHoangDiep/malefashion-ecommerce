package com.malefashion.inventory;

import com.malefashion.inventory.application.ReserveStockService;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.MessagingTopology;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Exercises the listener retry policy against a real broker. The business service is spied to inject
 * infrastructure failures; the message still travels through the real queue, retry advice and DLQ.
 */
class ListenerRetryIntegrationTest extends IntegrationTestBase {

    private static final String COMMANDS_DLQ = MessagingTopology.INVENTORY_COMMANDS_QUEUE + ".dlq";
    private static final String TEE_SKU = "MF-TEE-BLACK-M";
    private static final int MAX_ATTEMPTS = 3;

    @MockitoSpyBean
    private ReserveStockService reserveStockService;

    @Test
    void infrastructureFailureIsRetriedThreeTimesThenDeadLettered() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(reserveStockService).handle(any());

        publishReserve(eventId, orderId, variantId, 2);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(COMMANDS_DLQ) == 1);

        verify(reserveStockService, times(MAX_ATTEMPTS)).handle(any());
        assertThat(processedMessageCount(eventId)).isZero();
        assertThat(reservationRepository.count()).isZero();

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(5);
        assertThat(stock.getReservedQuantity()).isZero();

        awaitQueueEmpty(MessagingTopology.INVENTORY_COMMANDS_QUEUE);
        assertThat(messageCount(COMMANDS_DLQ)).isEqualTo(1);
    }

    @Test
    void twoInfrastructureFailuresThenSuccessPublishStockReservedExactlyOnce() {
        UUID variantId = seedStock(TEE_SKU, 5);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doCallRealMethod()
                .when(reserveStockService).handle(any());

        publishReserve(eventId, orderId, variantId, 2);

        awaitPublishedEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId);
        awaitQueueEmpty(MessagingTopology.INVENTORY_COMMANDS_QUEUE);

        verify(reserveStockService, times(MAX_ATTEMPTS)).handle(any());
        assertThat(processedMessageCount(eventId)).isEqualTo(1);
        assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId)).hasSize(1);
        assertThat(messageCount(COMMANDS_DLQ)).isZero();

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(3);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
        assertThat(reservationRepository.count()).isEqualTo(1);

        assertNoEvent(MessageTypes.EVENT_STOCK_RESERVED, orderId, Duration.ofSeconds(1));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxEvents(MessageTypes.EVENT_STOCK_RESERVED, orderId)).hasSize(1));
    }

    @Test
    void malformedJsonIsDeadLetteredOnceWithoutRequeue() {
        UUID eventId = UUID.randomUUID();

        sendRawCommand(MessageTypes.COMMAND_RESERVE_STOCK, eventId, "{ this is not json");

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> messageCount(COMMANDS_DLQ) == 1);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(messageCount(MessagingTopology.INVENTORY_COMMANDS_QUEUE)).isZero();
            assertThat(messageCount(COMMANDS_DLQ)).isEqualTo(1);
        });

        verify(reserveStockService, never()).handle(any());
        assertThat(processedMessageCount(eventId)).isZero();
    }

    private UUID seedStock(String sku, int quantity) {
        UUID variantId = UUID.randomUUID();
        stockItemRepository.save(StockItem.create(variantId, sku, quantity, Instant.now()));
        return variantId;
    }

    private void publishReserve(UUID eventId, UUID orderId, UUID variantId, int quantity) {
        publishCommand(MessageTypes.COMMAND_RESERVE_STOCK, eventId, orderId,
                new ReserveStockPayload(orderId,
                        List.of(new ReserveStockPayload.Item(variantId, TEE_SKU, quantity))));
    }
}
