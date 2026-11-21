package com.malefashion.order.infrastructure.messaging;

/**
 * failureCode của payment.failed theo contract.
 */
public enum PaymentFailureCode {
    PAYMENT_DECLINED,
    PAYMENT_TIMEOUT
}
