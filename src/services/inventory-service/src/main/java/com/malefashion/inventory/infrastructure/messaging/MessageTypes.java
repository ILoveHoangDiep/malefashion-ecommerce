package com.malefashion.inventory.infrastructure.messaging;

public final class MessageTypes {

    public static final String COMMAND_RESERVE_STOCK = "inventory.reserve-stock";
    public static final String COMMAND_COMMIT_STOCK = "inventory.commit-stock";
    public static final String COMMAND_RELEASE_STOCK = "inventory.release-stock";

    public static final String EVENT_STOCK_RESERVED = "inventory.stock-reserved";
    public static final String EVENT_STOCK_RESERVATION_FAILED = "inventory.stock-reservation-failed";
    public static final String EVENT_STOCK_COMMITTED = "inventory.stock-committed";
    public static final String EVENT_STOCK_COMMIT_FAILED = "inventory.stock-commit-failed";
    public static final String EVENT_STOCK_RELEASED = "inventory.stock-released";

    private MessageTypes() {
    }
}
