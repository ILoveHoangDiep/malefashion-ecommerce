package com.malefashion.order.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Cấu hình của order-service. Mọi giá trị sai làm context không khởi động thay vì chỉ phát hiện
 * lúc saga đang chạy.
 */
@Validated
@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        @NotNull @Valid Saga saga,
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal shippingFee,
        @Min(1) long timeoutScanIntervalMs,
        @NotBlank String internalApiKey,
        @NotBlank String cartBaseUrl,
        @NotBlank String catalogBaseUrl,
        boolean mockScenarioEnabled,
        @NotNull @Valid Outbox outbox,
        @NotNull @Valid Messaging messaging
) {

    public record Saga(
            @NotNull @DurationMin(seconds = 1) Duration timeout,
            @NotNull @DurationMin(seconds = 1) Duration compensationTimeout,
            @Min(0) int refundMaxRetries,
            @NotNull @DurationMin(seconds = 1) Duration refundRetryBackoff,
            @DecimalMin(value = "1.0", inclusive = true) double refundRetryBackoffMultiplier,
            @NotNull @DurationMin(seconds = 1) Duration commitTimeout,
            @Min(0) int commitMaxRetries,
            @NotNull @DurationMin(seconds = 1) Duration commitRetryBackoff,
            @DecimalMin(value = "1.0", inclusive = true) double commitRetryBackoffMultiplier
    ) {
    }

    public record Outbox(
            @Min(1) long publishIntervalMs,
            @Min(1) int batchSize,
            @NotNull @DurationMin(millis = 1) Duration confirmTimeout,
            @NotNull @Valid Publisher publisher
    ) {

        public record Publisher(
                boolean enabled
        ) {
        }
    }

    public record Messaging(
            @NotBlank String consumerName,
            @NotNull @Valid Listener listener,
            @NotNull @Valid Retry retry
    ) {

        public record Listener(
                boolean autoStartup,
                @Min(1) int concurrency,
                @NotBlank String queue
        ) {
        }

        public record Retry(
                @Min(1) int maxAttempts,
                @Min(1) long initialIntervalMs,
                @DecimalMin(value = "1.0", inclusive = true) double multiplier,
                @Min(1) long maxIntervalMs
        ) {
        }
    }
}
