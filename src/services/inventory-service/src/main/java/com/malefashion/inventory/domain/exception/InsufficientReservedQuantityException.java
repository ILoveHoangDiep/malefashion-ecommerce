package com.malefashion.inventory.domain.exception;

import java.util.UUID;

public class InsufficientReservedQuantityException extends DomainException {

    private final UUID variantId;
    private final int requestedQuantity;
    private final int reservedQuantity;

    public InsufficientReservedQuantityException(UUID variantId, int requestedQuantity, int reservedQuantity) {
        super("Insufficient reserved quantity for variant " + variantId
                + ": requested " + requestedQuantity + ", reserved " + reservedQuantity);
        this.variantId = variantId;
        this.requestedQuantity = requestedQuantity;
        this.reservedQuantity = reservedQuantity;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }
}
