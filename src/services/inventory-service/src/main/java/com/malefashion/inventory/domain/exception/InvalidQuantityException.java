package com.malefashion.inventory.domain.exception;

public class InvalidQuantityException extends DomainException {

    public InvalidQuantityException(int quantity) {
        super("Quantity must be greater than zero, but was " + quantity);
    }
}
