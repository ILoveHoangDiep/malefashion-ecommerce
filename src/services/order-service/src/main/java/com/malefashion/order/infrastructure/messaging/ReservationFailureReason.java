package com.malefashion.order.infrastructure.messaging;

/**
 * reason của inventory.stock-reservation-failed theo contract.
 */
public enum ReservationFailureReason {
    INSUFFICIENT_STOCK,
    VARIANT_NOT_FOUND,
    CONCURRENT_MODIFICATION
}
