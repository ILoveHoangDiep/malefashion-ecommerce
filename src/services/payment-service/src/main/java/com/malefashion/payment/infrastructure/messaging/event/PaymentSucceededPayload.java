package com.malefashion.payment.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentSucceededPayload(
        UUID orderId,
        UUID paymentId,
        Money amount,
        String providerTransactionId,
        Instant paidAt
) {
}
