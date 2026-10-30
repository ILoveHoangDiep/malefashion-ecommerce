package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries the reservation attempt with an exponential backoff.
 *
 * <p>There is deliberately no {@code @Transactional} on this class: every attempt must run in its own
 * transaction (opened by {@link ReserveStockTransactionService}). Once a transaction has failed, it is
 * rollback-only and its persistence context is stale, so reusing it for the next attempt would either
 * commit nothing or fail on the first flush.</p>
 */
@Service
public class ReserveStockService {

    private static final Logger log = LoggerFactory.getLogger(ReserveStockService.class);

    private final ReserveStockTransactionService reserveStockTransactionService;
    private final InventoryProperties properties;

    public ReserveStockService(ReserveStockTransactionService reserveStockTransactionService,
                               InventoryProperties properties) {
        this.reserveStockTransactionService = reserveStockTransactionService;
        this.properties = properties;
    }

    public void handle(MessageEnvelope<ReserveStockPayload> envelope) {
        int maxAttempts = properties.reservation().reserveMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                reserveStockTransactionService.attempt(envelope);
                return;
            } catch (OptimisticLockingFailureException
                     | PessimisticLockingFailureException
                     | DataIntegrityViolationException failure) {
                log.warn("Reserve attempt {}/{} failed for event {} ({}): {}",
                        attempt, maxAttempts, envelope.eventId(), envelope.payload().orderId(),
                        failure.toString());
                if (attempt == maxAttempts) {
                    reserveStockTransactionService.recordConcurrentFailure(envelope);
                    return;
                }
                backOff(attempt);
            }
        }
    }

    private void backOff(int attempt) {
        long baseDelay = properties.reservation().reserveRetryBaseDelayMs();
        long jitterBound = properties.reservation().reserveRetryJitterMs();
        long delay = baseDelay * (1L << (attempt - 1))
                + (jitterBound > 0 ? ThreadLocalRandom.current().nextLong(jitterBound + 1) : 0);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before retrying reservation", interrupted);
        }
    }
}
