package com.malefashion.order.infrastructure.messaging;

import java.util.Set;

/**
 * Tên command/event theo contract. Envelope dùng chung, {@code eventType} mang command type với
 * command.
 */
public final class MessageTypes {

    public static final String COMMAND_RESERVE_STOCK = "inventory.reserve-stock";
    public static final String COMMAND_COMMIT_STOCK = "inventory.commit-stock";
    public static final String COMMAND_RELEASE_STOCK = "inventory.release-stock";
    public static final String COMMAND_PROCESS_PAYMENT = "payment.process-payment";
    public static final String COMMAND_REFUND_PAYMENT = "payment.refund-payment";

    public static final String EVENT_ORDER_CREATED = "order.created";
    public static final String EVENT_ORDER_CONFIRMED = "order.confirmed";
    public static final String EVENT_ORDER_CANCELLED = "order.cancelled";

    public static final String EVENT_STOCK_RESERVED = "inventory.stock-reserved";
    public static final String EVENT_STOCK_RESERVATION_FAILED =
            "inventory.stock-reservation-failed";
    public static final String EVENT_STOCK_COMMITTED = "inventory.stock-committed";
    public static final String EVENT_STOCK_COMMIT_FAILED = "inventory.stock-commit-failed";
    public static final String EVENT_STOCK_RELEASED = "inventory.stock-released";
    public static final String EVENT_PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String EVENT_PAYMENT_FAILED = "payment.failed";
    public static final String EVENT_PAYMENT_REFUNDED = "payment.refunded";

    /**
     * Đúng 8 event mà saga queue nhận; mọi type khác là lỗi vĩnh viễn.
     */
    public static final Set<String> INBOUND_EVENT_TYPES = Set.of(
            EVENT_STOCK_RESERVED,
            EVENT_STOCK_RESERVATION_FAILED,
            EVENT_STOCK_COMMITTED,
            EVENT_STOCK_COMMIT_FAILED,
            EVENT_STOCK_RELEASED,
            EVENT_PAYMENT_SUCCEEDED,
            EVENT_PAYMENT_FAILED,
            EVENT_PAYMENT_REFUNDED);

    private MessageTypes() {
    }
}
