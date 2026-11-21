package com.malefashion.order.domain.saga;

/**
 * Tập reason của inventory.release-stock / inventory.stock-released theo contract.
 */
public enum StockReleaseReason {
    PAYMENT_FAILED,
    SAGA_TIMEOUT,
    RESERVATION_EXPIRED
}
