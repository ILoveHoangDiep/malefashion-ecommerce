package com.malefashion.inventory.infrastructure.messaging.event;

public enum ReservationFailureReason {
    INSUFFICIENT_STOCK,
    VARIANT_NOT_FOUND,
    CONCURRENT_MODIFICATION
}
