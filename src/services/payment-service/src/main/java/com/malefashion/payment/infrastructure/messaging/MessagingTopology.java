package com.malefashion.payment.infrastructure.messaging;

/**
 * Tên exchange/queue do deploy/rabbitmq/definitions.json sở hữu. Service không tự khai báo.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "malefashion.events";
    public static final String COMMANDS_EXCHANGE = "malefashion.commands";
    public static final String PAYMENT_COMMANDS_QUEUE = "payment-service.commands";
    public static final String PAYMENT_COMMANDS_DLQ = "payment-service.commands.dlq";
    public static final String ORDER_SAGA_EVENTS_QUEUE = "order-service.saga.events";

    private MessagingTopology() {
    }
}
