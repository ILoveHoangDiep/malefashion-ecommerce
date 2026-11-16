package com.malefashion.payment.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailedPayload(
        UUID orderId,
        UUID paymentId,
        Money amount,
        String failureCode,
        String failureMessage,
        Instant failedAt
) {
}
