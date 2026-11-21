package com.malefashion.order.domain.model;

public enum SagaStep {
    AWAITING_RESERVATION,
    AWAITING_PAYMENT,
    AWAITING_COMMIT,
    AWAITING_RELEASE,
    AWAITING_REFUND,
    DONE
}
