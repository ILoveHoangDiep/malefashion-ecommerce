package com.malefashion.payment.infrastructure.messaging;

import com.malefashion.payment.application.InvalidCommandException;
import com.malefashion.payment.application.ProcessPaymentService;
import com.malefashion.payment.application.RefundPaymentService;
import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.application.command.RefundPaymentCommand;
import com.malefashion.payment.config.PaymentProperties;
import com.malefashion.payment.infrastructure.persistence.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Chạy dedupe và nghiệp vụ trong MỘT transaction.
 *
 * <p>INSERT processed_messages ... ON CONFLICT DO NOTHING đứng trước nghiệp vụ: nếu message đã xử lý,
 * transaction kết thúc mà không chạy lại gì (ack bình thường). Nếu nghiệp vụ ném lỗi, cả marker lẫn
 * thay đổi payment/outbox rollback cùng nhau.</p>
 */
@Component
public class PaymentCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandProcessor.class);

    private final TransactionTemplate transactionTemplate;
    private final ProcessedMessageRepository processedMessageRepository;
    private final ProcessPaymentService processPaymentService;
    private final RefundPaymentService refundPaymentService;
    private final PaymentProperties properties;

    public PaymentCommandProcessor(PlatformTransactionManager transactionManager,
                                   ProcessedMessageRepository processedMessageRepository,
                                   ProcessPaymentService processPaymentService,
                                   RefundPaymentService refundPaymentService,
                                   PaymentProperties properties) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.processedMessageRepository = processedMessageRepository;
        this.processPaymentService = processPaymentService;
        this.refundPaymentService = refundPaymentService;
        this.properties = properties;
    }

    public void processPayment(UUID eventId, ProcessPaymentCommand command, String traceId) {
        inTransaction(eventId, () -> processPaymentService.process(command, traceId));
    }

    public void refundPayment(UUID eventId, RefundPaymentCommand command, String traceId) {
        inTransaction(eventId, () -> refundPaymentService.refund(command, traceId));
    }

    private void inTransaction(UUID eventId, Runnable action) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                int inserted = processedMessageRepository.insertIfAbsent(
                        properties.messaging().consumerName(), eventId, now);
                if (inserted == 0) {
                    log.info("Skipping duplicate command {}", eventId);
                    return;
                }
                action.run();
            });
        } catch (InvalidCommandException invalid) {
            // Lỗi vĩnh viễn: transaction đã rollback (không có marker), message đi thẳng DLQ.
            throw new AmqpRejectAndDontRequeueException(
                    "Invalid command " + eventId + ": " + invalid.getMessage(), invalid);
        }
    }
}
