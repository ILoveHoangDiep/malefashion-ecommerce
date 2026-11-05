package com.malefashion.payment.application;

import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentScenario;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.infrastructure.messaging.event.PaymentEventFactory;
import com.malefashion.payment.infrastructure.persistence.PaymentRepository;
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
 * Xử lý payment.process-payment trên PostgreSQL.
 *
 * <p>Method chạy trong transaction do caller mở (REQUIRED); OutboxService ghi event vào cùng
 * transaction nên thay đổi payment và event luôn commit/rollback cùng nhau.</p>
 */
@Service
public class ProcessPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentService.class);

    static final String FAILURE_CODE_DECLINED = "PAYMENT_DECLINED";
    static final String FAILURE_CODE_TIMEOUT = "PAYMENT_TIMEOUT";
    static final String FAILURE_MESSAGE_DECLINED = "Mock payment declined";
    static final String FAILURE_MESSAGE_TIMEOUT = "Mock payment timed out";

    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;
    private final MockScenarioResolver mockScenarioResolver;
    private final ProviderTransactionIdGenerator providerTransactionIdGenerator;
    private final PaymentEventFactory paymentEventFactory;

    public ProcessPaymentService(PaymentRepository paymentRepository,
                                 OutboxService outboxService,
                                 MockScenarioResolver mockScenarioResolver,
                                 ProviderTransactionIdGenerator providerTransactionIdGenerator,
                                 PaymentEventFactory paymentEventFactory) {
        this.paymentRepository = paymentRepository;
        this.outboxService = outboxService;
        this.mockScenarioResolver = mockScenarioResolver;
        this.providerTransactionIdGenerator = providerTransactionIdGenerator;
        this.paymentEventFactory = paymentEventFactory;
    }

    @Transactional
    public void process(ProcessPaymentCommand command, String traceId) {
        validate(command);

        PaymentScenario scenario = mockScenarioResolver.resolve(command.mockScenario());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        UUID candidateId = UUID.randomUUID();
        boolean success = scenario == PaymentScenario.SUCCESS;
        String providerTransactionId = success ? providerTransactionIdGenerator.next() : null;
        PaymentStatus status = success ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        String failureCode = switch (scenario) {
            case SUCCESS -> null;
            case FAILURE -> FAILURE_CODE_DECLINED;
            case TIMEOUT -> FAILURE_CODE_TIMEOUT;
        };
        String failureMessage = switch (scenario) {
            case SUCCESS -> null;
            case FAILURE -> FAILURE_MESSAGE_DECLINED;
            case TIMEOUT -> FAILURE_MESSAGE_TIMEOUT;
        };

        int inserted = paymentRepository.insertIfAbsent(
                candidateId,
                command.orderId(),
                command.amount(),
                Payment.CURRENCY_VND,
                status.name(),
                scenario.name(),
                providerTransactionId,
                failureCode,
                failureMessage,
                success ? now : null,
                success ? null : now,
                now);

        Payment payment = paymentRepository.findByOrderId(command.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment for order " + command.orderId() + " vanished after insert"));

        if (inserted == 0) {
            log.info("process-payment replayed for existing order {} with status {}",
                    command.orderId(), payment.getStatus());
            handleExistingPayment(payment, command, traceId);
            return;
        }

        appendOutcomeEvent(payment, traceId);
    }

    private void handleExistingPayment(Payment payment, ProcessPaymentCommand command, String traceId) {
        if (command.amount().compareTo(payment.getAmount()) != 0) {
            log.warn("process-payment for order {} carries amount {} but stored payment amount is {}; keeping stored data",
                    payment.getOrderId(), command.amount(), payment.getAmount());
        }

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.warn("process-payment ignored for order {}: payment {} is already REFUNDED",
                    payment.getOrderId(), payment.getId());
            return;
        }

        appendOutcomeEvent(payment, traceId);
    }

    private void appendOutcomeEvent(Payment payment, String traceId) {
        switch (payment.getStatus()) {
            case SUCCEEDED -> outboxService.append(paymentEventFactory.paymentSucceeded(
                    payment.getOrderId(),
                    payment.getId(),
                    payment.getAmount(),
                    payment.getProviderTransactionId(),
                    payment.getPaidAt(),
                    traceId));
            case FAILED -> {
                if (mockScenarioResolver.isSilentOnTimeout()
                        && FAILURE_CODE_TIMEOUT.equals(payment.getFailureCode())) {
                    log.info("Silent timeout enabled: payment {} for order {} emits no event",
                            payment.getId(), payment.getOrderId());
                    return;
                }
                outboxService.append(paymentEventFactory.paymentFailed(
                        payment.getOrderId(),
                        payment.getId(),
                        payment.getAmount(),
                        payment.getFailureCode(),
                        payment.getFailureMessage(),
                        payment.getFailedAt(),
                        traceId));
            }
            case REFUNDED -> log.warn("No payment event emitted for order {}: payment {} is REFUNDED",
                    payment.getOrderId(), payment.getId());
        }
    }

    private void validate(ProcessPaymentCommand command) {
        if (command == null) {
            throw new InvalidCommandException("process-payment command must not be null");
        }
        if (command.orderId() == null) {
            throw new InvalidCommandException("orderId must be a UUID");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new InvalidCommandException("amount must be greater than zero");
        }
        if (!Payment.CURRENCY_VND.equals(command.currency())) {
            throw new InvalidCommandException("currency must be VND");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new InvalidCommandException("idempotencyKey must not be blank");
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
