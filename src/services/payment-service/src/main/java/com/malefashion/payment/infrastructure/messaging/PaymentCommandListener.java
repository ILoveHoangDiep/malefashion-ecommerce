package com.malefashion.payment.infrastructure.messaging;

import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.application.command.RefundPaymentCommand;
import com.malefashion.payment.infrastructure.messaging.CommandEnvelopeReader.CommandEnvelope;
import com.malefashion.payment.infrastructure.messaging.command.ProcessPaymentPayload;
import com.malefashion.payment.infrastructure.messaging.command.RefundPaymentPayload;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận command của saga từ {@code payment-service.commands}.
 *
 * <p>Listener cố tình không có {@code @Transactional}: transaction bao gồm dedupe + nghiệp vụ do
 * {@link PaymentCommandProcessor} mở.</p>
 */
@Component
public class PaymentCommandListener {

    private final CommandEnvelopeReader envelopeReader;
    private final PaymentCommandProcessor commandProcessor;

    public PaymentCommandListener(CommandEnvelopeReader envelopeReader,
                                  PaymentCommandProcessor commandProcessor) {
        this.envelopeReader = envelopeReader;
        this.commandProcessor = commandProcessor;
    }

    @RabbitListener(
            queues = MessagingTopology.PAYMENT_COMMANDS_QUEUE,
            autoStartup = "${payment.messaging.listener.auto-startup:true}")
    public void onCommand(Message message) {
        CommandEnvelope envelope = envelopeReader.read(message);

        switch (envelope.commandType()) {
            case MessageTypes.COMMAND_PROCESS_PAYMENT -> {
                ProcessPaymentPayload payload = envelopeReader.convert(
                        envelope.payload(), ProcessPaymentPayload.class, message);
                commandProcessor.processPayment(
                        envelope.eventId(), toCommand(payload, message), envelope.traceId());
            }
            case MessageTypes.COMMAND_REFUND_PAYMENT -> {
                RefundPaymentPayload payload = envelopeReader.convert(
                        envelope.payload(), RefundPaymentPayload.class, message);
                commandProcessor.refundPayment(
                        envelope.eventId(), toCommand(payload, message), envelope.traceId());
            }
            default -> throw envelopeReader.reject(
                    "unsupported command type: " + envelope.commandType(), message);
        }
    }

    private ProcessPaymentCommand toCommand(ProcessPaymentPayload payload, Message message) {
        try {
            return payload.toCommand();
        } catch (NumberFormatException invalidAmount) {
            throw envelopeReader.reject(
                    "payload.amount.amount is not a decimal: " + invalidAmount.getMessage(), message);
        }
    }

    private RefundPaymentCommand toCommand(RefundPaymentPayload payload, Message message) {
        try {
            return payload.toCommand();
        } catch (NumberFormatException invalidAmount) {
            throw envelopeReader.reject(
                    "payload.amount.amount is not a decimal: " + invalidAmount.getMessage(), message);
        }
    }
}
