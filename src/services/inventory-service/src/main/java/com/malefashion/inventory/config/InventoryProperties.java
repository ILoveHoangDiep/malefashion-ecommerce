package com.malefashion.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(
        Reservation reservation,
        Outbox outbox,
        Messaging messaging
) {

    public record Reservation(
            Duration ttl,
            long expiryScanIntervalMs,
            long expiryInitialDelayMs,
            int maxExpiriesPerRun,
            int reserveMaxAttempts,
            long reserveRetryBaseDelayMs,
            long reserveRetryJitterMs
    ) {
    }

    public record Outbox(
            int batchSize,
            long publishIntervalMs,
            long initialDelayMs,
            long confirmTimeoutMs
    ) {
    }

    public record Messaging(
            String consumerName,
            Retry retry
    ) {
    }

    public record Retry(
            int maxAttempts,
            long initialIntervalMs,
            double multiplier,
            long maxIntervalMs
    ) {
    }
}
