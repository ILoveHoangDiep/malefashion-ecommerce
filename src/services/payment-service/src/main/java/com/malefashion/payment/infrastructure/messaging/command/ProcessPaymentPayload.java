package com.malefashion.payment.infrastructure.messaging.command;

import com.malefashion.payment.application.command.ProcessPaymentCommand;

import java.util.UUID;

/**
 * Payload của payment.process-payment đúng như contract; chuyển sang command nghiệp vụ của Prompt 2.
 */
public record ProcessPaymentPayload(
        UUID orderId,
        CommandMoney amount,
        String idempotencyKey,
        String mockScenario
) {

    public ProcessPaymentCommand toCommand() {
        return new ProcessPaymentCommand(
                orderId,
                amount == null ? null : amount.toAmount(),
                amount == null ? null : amount.currency(),
                idempotencyKey,
                mockScenario);
    }
}
