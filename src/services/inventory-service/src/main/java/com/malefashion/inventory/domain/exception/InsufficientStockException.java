package com.malefashion.inventory.domain.exception;

import java.util.UUID;

public class InsufficientStockException extends DomainException {

    private final UUID variantId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(UUID variantId, int requestedQuantity, int availableQuantity) {
        super("Insufficient stock for variant " + variantId
                + ": requested " + requestedQuantity + ", available " + availableQuantity);
        this.variantId = variantId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
