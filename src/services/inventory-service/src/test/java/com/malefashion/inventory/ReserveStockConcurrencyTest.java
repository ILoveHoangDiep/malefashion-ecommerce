package com.malefashion.inventory;

import com.malefashion.inventory.application.ReserveStockService;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20 threads reserve one unit each from a single variant that has 10 units. Everything runs on the real
 * PostgreSQL container, so row locks, transactions and CHECK constraints are exercised for real.
 */
class ReserveStockConcurrencyTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ReserveStockConcurrencyTest.class);

    private static final int THREADS = 20;
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private ReserveStockService reserveStockService;

    @Test
    void exactlyTenOfTwentyConcurrentReservationsSucceedWithoutOverselling() throws Exception {
        UUID variantId = UUID.randomUUID();
        String sku = "MF-TEE-BLACK-M";
        stockItemRepository.save(StockItem.create(variantId, sku, INITIAL_STOCK, Instant.now()));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    UUID orderId = UUID.randomUUID();
                    ReserveStockPayload payload = new ReserveStockPayload(
                            orderId, List.of(new ReserveStockPayload.Item(variantId, sku, 1)));
                    MessageEnvelope<ReserveStockPayload> command = new MessageEnvelope<>(
                            UUID.randomUUID(),
                            MessageTypes.COMMAND_RESERVE_STOCK,
                            1,
                            Instant.now(),
                            "order-service",
                            orderId,
                            TRACE_ID,
                            payload);
                    reserveStockService.handle(command);
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            // While the workers run, available must never be observed below zero.
            boolean allDone = false;
            while (!allDone) {
                Integer available = jdbcTemplate.queryForObject(
                        "SELECT available_quantity FROM stock_items WHERE variant_id = ?",
                        Integer.class, variantId);
                assertThat(available).isGreaterThanOrEqualTo(0);
                allDone = futures.stream().allMatch(Future::isDone);
                Thread.sleep(5);
            }

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        StockItem stock = stockItemRepository.findById(variantId).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isZero();
        assertThat(stock.getReservedQuantity()).isEqualTo(INITIAL_STOCK);
        assertThat(stock.getAvailableQuantity() + stock.getReservedQuantity()).isEqualTo(INITIAL_STOCK);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        long reservedEvents = countType(events, MessageTypes.EVENT_STOCK_RESERVED);
        List<OutboxEvent> failedEvents = events.stream()
                .filter(event -> MessageTypes.EVENT_STOCK_RESERVATION_FAILED.equals(event.getEventType()))
                .toList();

        Map<String, Long> failedByReason = new LinkedHashMap<>();
        failedByReason.put("INSUFFICIENT_STOCK", 0L);
        failedByReason.put("CONCURRENT_MODIFICATION", 0L);
        failedEvents.forEach(event -> failedByReason.merge(
                event.getPayload().path("payload").path("reason").asText(), 1L, Long::sum));

        // Logged for observation; the contract of this test only fixes the success/failure split.
        log.info("CONCURRENCY_REASON_COUNTS reservedEvents={} failedEvents={} failedByReason={}",
                reservedEvents, failedEvents.size(), failedByReason);

        assertThat(reservedEvents).isEqualTo(10);
        assertThat(failedEvents).hasSize(10);
        assertThat(failedEvents)
                .allSatisfy(event -> assertThat(event.getPayload().path("payload").path("reason").asText())
                        .isIn("INSUFFICIENT_STOCK", "CONCURRENT_MODIFICATION"));
        assertThat(reservationRepository.count()).isEqualTo(10);
    }

    private static long countType(List<OutboxEvent> events, String eventType) {
        return events.stream().filter(event -> eventType.equals(event.getEventType())).count();
    }
}
