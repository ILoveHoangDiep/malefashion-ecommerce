package com.malefashion.payment.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentRefundedPayload(
        UUID orderId,
        UUID paymentId,
        UUID refundId,
        Money amount,
        Instant refundedAt
) {
}
