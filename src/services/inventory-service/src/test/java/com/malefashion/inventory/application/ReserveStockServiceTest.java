package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the retry loop itself. Backoff is set to zero so the tests are fast; the
 * transactional work is mocked away, which is exactly the boundary this class owns.
 */
@ExtendWith(MockitoExtension.class)
class ReserveStockServiceTest {

    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private ReserveStockTransactionService transactionService;

    private ReserveStockService service;
    private MessageEnvelope<ReserveStockPayload> envelope;

    @BeforeEach
    void setUp() {
        InventoryProperties properties = new InventoryProperties(
                new InventoryProperties.Reservation(Duration.ofMinutes(15), 60000, 60000, 100,
                        MAX_ATTEMPTS, 0, 0),
                new InventoryProperties.Outbox(100, 500, 1000, 5000),
                new InventoryProperties.Messaging("inventory-service",
                        new InventoryProperties.Retry(3, 500, 2.0, 5000)));
        service = new ReserveStockService(transactionService, properties);

        UUID variantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ReserveStockPayload payload = new ReserveStockPayload(
                orderId, List.of(new ReserveStockPayload.Item(variantId, "MF-TEE-BLACK-M", 1)));
        envelope = new MessageEnvelope<>(
                UUID.randomUUID(), MessageTypes.COMMAND_RESERVE_STOCK, 1, Instant.now(),
                "order-service", orderId, "4bf92f3577b34da6a3ce929d0e0e4736", payload);
    }

    @Test
    void retriesTwiceThenSucceedsWithoutRecordingConcurrentFailure() {
        doThrow(new OptimisticLockingFailureException("conflict"))
                .doThrow(new OptimisticLockingFailureException("conflict"))
                .doNothing()
                .when(transactionService).attempt(envelope);

        service.handle(envelope);

        verify(transactionService, times(3)).attempt(envelope);
        verify(transactionService, never()).recordConcurrentFailure(any());
    }

    @Test
    void exhaustsEveryAttemptThenRecordsOneConcurrentFailure() {
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(transactionService).attempt(envelope);

        service.handle(envelope);

        verify(transactionService, times(MAX_ATTEMPTS)).attempt(envelope);
        verify(transactionService, times(1)).recordConcurrentFailure(envelope);
    }

    @Test
    void doesNotRetryFailuresThatAreNotLockConflicts() {
        doThrow(new IllegalStateException("boom"))
                .when(transactionService).attempt(envelope);

        assertThatThrownBy(() -> service.handle(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(transactionService, times(1)).attempt(envelope);
        verify(transactionService, never()).recordConcurrentFailure(any());
    }
}
