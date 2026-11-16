package com.malefashion.inventory.infrastructure.messaging.event;

public enum ReleaseEventReason {
    PAYMENT_FAILED,
    SAGA_TIMEOUT,
    RESERVATION_EXPIRED
}
