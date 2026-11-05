package com.malefashion.payment.infrastructure.messaging.event;

import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tạo envelope + payload cho ba event payment-service phát ra.
 *
 * <p>Mọi event mang {@code eventId} mới kể cả khi phát lại event cũ; {@code occurredAt} luôn cắt về
 * mili giây và {@code traceId} bắt buộc đúng 32 ký tự hex lowercase.</p>
 */
@Component
public class PaymentEventFactory {

    public static final String PRODUCER = "payment-service";
    public static final int EVENT_VERSION = 1;

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    public MessageEnvelope<PaymentSucceededPayload> paymentSucceeded(UUID orderId,
                                                                    UUID paymentId,
                                                                    BigDecimal amount,
                                                                    String providerTransactionId,
                                                                    Instant paidAt,
                                                                    String traceId) {
        return envelope(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId, traceId,
                new PaymentSucceededPayload(orderId, paymentId, Money.vnd(amount),
                        providerTransactionId, toMillis(paidAt)));
    }

    public MessageEnvelope<PaymentFailedPayload> paymentFailed(UUID orderId,
                                                              UUID paymentId,
                                                              BigDecimal amount,
                                                              String failureCode,
                                                              String failureMessage,
                                                              Instant failedAt,
                                                              String traceId) {
        return envelope(MessageTypes.EVENT_PAYMENT_FAILED, orderId, traceId,
                new PaymentFailedPayload(orderId, paymentId, Money.vnd(amount),
                        failureCode, failureMessage, toMillis(failedAt)));
    }

    public MessageEnvelope<PaymentRefundedPayload> paymentRefunded(UUID orderId,
                                                                   UUID paymentId,
                                                                   UUID refundId,
                                                                   BigDecimal amount,
                                                                   Instant refundedAt,
                                                                   String traceId) {
        return envelope(MessageTypes.EVENT_PAYMENT_REFUNDED, orderId, traceId,
                new PaymentRefundedPayload(orderId, paymentId, refundId, Money.vnd(amount),
                        toMillis(refundedAt)));
    }

    private static <T> MessageEnvelope<T> envelope(String eventType,
                                                   UUID correlationId,
                                                   String traceId,
                                                   T payload) {
        return new MessageEnvelope<>(
                UUID.randomUUID(),
                eventType,
                EVENT_VERSION,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                PRODUCER,
                correlationId,
                normalizeTraceId(traceId),
                payload
        );
    }

    static String normalizeTraceId(String traceId) {
        if (traceId != null && TRACE_ID.matcher(traceId).matches()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Instant toMillis(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
