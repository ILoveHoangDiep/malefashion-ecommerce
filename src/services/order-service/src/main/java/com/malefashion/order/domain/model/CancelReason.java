package com.malefashion.order.domain.model;

public enum CancelReason {
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    SAGA_TIMEOUT,
    STOCK_COMMIT_FAILED
}
