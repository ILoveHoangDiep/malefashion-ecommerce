package com.malefashion.order.infrastructure.messaging;

/**
 * Tên exchange/queue do {@code deploy/rabbitmq/definitions.json} sở hữu. Service chỉ tham chiếu,
 * không khai báo.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "malefashion.events";
    public static final String COMMANDS_EXCHANGE = "malefashion.commands";

    public static final String ORDER_SAGA_EVENTS_QUEUE = "order-service.saga.events";
    public static final String ORDER_SAGA_EVENTS_DLQ = "order-service.saga.events.dlq";

    public static final String INVENTORY_COMMANDS_QUEUE = "inventory-service.commands";
    public static final String INVENTORY_COMMANDS_DLQ = "inventory-service.commands.dlq";
    public static final String NOTIFICATION_EVENTS_QUEUE = "notification-service.events";
    public static final String NOTIFICATION_EVENTS_DLQ = "notification-service.events.dlq";

    private MessagingTopology() {
    }
}
