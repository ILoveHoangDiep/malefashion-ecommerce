package com.malefashion.payment.application;

import com.malefashion.payment.application.command.RefundPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.Refund;
import com.malefashion.payment.infrastructure.messaging.event.PaymentEventFactory;
import com.malefashion.payment.infrastructure.persistence.PaymentRepository;
import com.malefashion.payment.infrastructure.persistence.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Xử lý payment.refund-payment (hoàn tiền toàn phần khi inventory commit thất bại).
 *
 * <p>Payment được khóa bằng {@code SELECT ... FOR UPDATE} để hai lệnh refund song song không tạo
 * hai refund. Refund, trạng thái payment và event payment.refunded nằm trong cùng transaction.</p>
 */
@Service
public class RefundPaymentService {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentService.class);

    static final String REFUND_REASON_STOCK_COMMIT_FAILED = "STOCK_COMMIT_FAILED";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxService outboxService;
    private final PaymentEventFactory paymentEventFactory;

    public RefundPaymentService(PaymentRepository paymentRepository,
                                RefundRepository refundRepository,
                                OutboxService outboxService,
                                PaymentEventFactory paymentEventFactory) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.outboxService = outboxService;
        this.paymentEventFactory = paymentEventFactory;
    }

    @Transactional
    public RefundOutcome refund(RefundPaymentCommand command, String traceId) {
        validate(command);

        Payment payment = paymentRepository.findByOrderIdForUpdate(command.orderId()).orElse(null);
        if (payment == null) {
            log.warn("refund-payment ignored: no payment for order {}", command.orderId());
            return RefundOutcome.NO_EVENT;
        }
        if (!payment.getId().equals(command.paymentId())) {
            log.warn("refund-payment ignored for order {}: command paymentId {} does not match stored payment {}",
                    command.orderId(), command.paymentId(), payment.getId());
            return RefundOutcome.NO_EVENT;
        }

        return switch (payment.getStatus()) {
            case FAILED -> {
                log.warn("refund-payment ignored for order {}: payment {} is FAILED",
                        payment.getOrderId(), payment.getId());
                yield RefundOutcome.NO_EVENT;
            }
            case SUCCEEDED -> refundSucceeded(payment, command, traceId);
            case REFUNDED -> replayRefund(payment, traceId);
        };
    }

    private RefundOutcome refundSucceeded(Payment payment,
                                          RefundPaymentCommand command,
                                          String traceId) {
        if (command.amount().compareTo(payment.getAmount()) != 0) {
            log.warn("refund-payment for order {} carries amount {} but paid amount is {}; refunding the paid amount",
                    payment.getOrderId(), command.amount(), payment.getAmount());
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Refund refund = Refund.create(
                UUID.randomUUID(),
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                REFUND_REASON_STOCK_COMMIT_FAILED,
                now);
        refundRepository.save(refund);
        payment.markRefunded(now);

        outboxService.append(paymentEventFactory.paymentRefunded(
                payment.getOrderId(),
                payment.getId(),
                refund.getId(),
                refund.getAmount(),
                refund.getRefundedAt(),
                traceId));
        return RefundOutcome.REFUNDED;
    }

    private RefundOutcome replayRefund(Payment payment, String traceId) {
        Refund refund = refundRepository.findByPaymentId(payment.getId()).orElse(null);
        if (refund == null) {
            log.warn("refund-payment ignored for order {}: payment {} is REFUNDED but no refund row exists",
                    payment.getOrderId(), payment.getId());
            return RefundOutcome.NO_EVENT;
        }

        outboxService.append(paymentEventFactory.paymentRefunded(
                payment.getOrderId(),
                payment.getId(),
                refund.getId(),
                refund.getAmount(),
                refund.getRefundedAt(),
                traceId));
        return RefundOutcome.EVENT_REPLAYED;
    }

    private void validate(RefundPaymentCommand command) {
        if (command == null) {
            throw new InvalidCommandException("refund-payment command must not be null");
        }
        if (command.orderId() == null) {
            throw new InvalidCommandException("orderId must be a UUID");
        }
        if (command.paymentId() == null) {
            throw new InvalidCommandException("paymentId must be a UUID");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new InvalidCommandException("amount must be greater than zero");
        }
        if (!Payment.CURRENCY_VND.equals(command.currency())) {
            throw new InvalidCommandException("currency must be VND");
        }
        if (!REFUND_REASON_STOCK_COMMIT_FAILED.equals(command.reason())) {
            throw new InvalidCommandException("reason must be STOCK_COMMIT_FAILED");
        }
        requireContractScale(command.amount());
    }

    private static void requireContractScale(BigDecimal amount) {
        try {
            amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooManyDecimals) {
            throw new InvalidCommandException("amount must have at most 2 decimal places");
        }
    }
}
