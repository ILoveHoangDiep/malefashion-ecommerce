package com.malefashion.payment.infrastructure.messaging.command;

import com.malefashion.payment.application.command.RefundPaymentCommand;

import java.util.UUID;

/**
 * Payload của payment.refund-payment đúng như contract; chuyển sang command nghiệp vụ của Prompt 2.
 */
public record RefundPaymentPayload(
        UUID orderId,
        UUID paymentId,
        CommandMoney amount,
        String reason
) {

    public RefundPaymentCommand toCommand() {
        return new RefundPaymentCommand(
                orderId,
                paymentId,
                amount == null ? null : amount.toAmount(),
                amount == null ? null : amount.currency(),
                reason);
    }
}
