package com.malefashion.order.domain.saga;

import java.time.Duration;
import java.util.Objects;

/**
 * Các hạn chờ của saga, tách khỏi Spring để domain test được bằng đồng hồ giả.
 */
public record SagaTiming(
        Duration compensationTimeout,
        int refundMaxRetries,
        Duration refundRetryBackoff,
        double refundRetryBackoffMultiplier
) {

    public SagaTiming {
        Objects.requireNonNull(compensationTimeout, "compensationTimeout must not be null");
        Objects.requireNonNull(refundRetryBackoff, "refundRetryBackoff must not be null");
        if (compensationTimeout.isZero() || compensationTimeout.isNegative()) {
            throw new IllegalArgumentException("compensationTimeout must be positive");
        }
        if (refundMaxRetries < 0) {
            throw new IllegalArgumentException("refundMaxRetries must be >= 0");
        }
        if (refundRetryBackoff.isZero() || refundRetryBackoff.isNegative()) {
            throw new IllegalArgumentException("refundRetryBackoff must be positive");
        }
        if (refundRetryBackoffMultiplier < 1.0) {
            throw new IllegalArgumentException("refundRetryBackoffMultiplier must be >= 1.0");
        }
    }
}
