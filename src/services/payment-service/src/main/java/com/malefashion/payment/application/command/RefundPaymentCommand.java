package com.malefashion.payment.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lệnh payment.refund-payment đã được parse từ envelope.
 */
public record RefundPaymentCommand(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason
) {
}
