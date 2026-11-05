package com.malefashion.payment.infrastructure.messaging;

public final class MessageTypes {

    public static final String COMMAND_PROCESS_PAYMENT = "payment.process-payment";
    public static final String COMMAND_REFUND_PAYMENT = "payment.refund-payment";

    public static final String EVENT_PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String EVENT_PAYMENT_FAILED = "payment.failed";
    public static final String EVENT_PAYMENT_REFUNDED = "payment.refunded";

    private MessageTypes() {
    }
}
