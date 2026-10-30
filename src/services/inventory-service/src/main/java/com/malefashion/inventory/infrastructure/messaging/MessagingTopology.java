package com.malefashion.inventory.infrastructure.messaging;

/**
 * Names owned by deploy/rabbitmq/definitions.json. The service never declares them.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "malefashion.events";
    public static final String COMMANDS_EXCHANGE = "malefashion.commands";
    public static final String INVENTORY_COMMANDS_QUEUE = "inventory-service.commands";
    public static final String ORDER_SAGA_EVENTS_QUEUE = "order-service.saga.events";

    private MessagingTopology() {
    }
}
