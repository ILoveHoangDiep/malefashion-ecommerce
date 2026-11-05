package com.malefashion.payment.infrastructure.messaging.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.payment.config.JacksonConfig;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventFactoryTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant PAID_AT = Instant.parse("2026-10-22T03:15:45.789123456Z");

    private final PaymentEventFactory factory = new PaymentEventFactory();
    private final ObjectMapper contractMapper = contractMapper();

    @Test
    void buildsSucceededEnvelopeWithContractFields() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        MessageEnvelope<PaymentSucceededPayload> envelope = factory.paymentSucceeded(
                orderId, paymentId, new BigDecimal("578000"), "MOCK-TXN-20261022-000001", PAID_AT, TRACE_ID);

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo(MessageTypes.EVENT_PAYMENT_SUCCEEDED);
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.producer()).isEqualTo("payment-service");
        assertThat(envelope.correlationId()).isEqualTo(orderId);
        assertThat(envelope.traceId()).isEqualTo(TRACE_ID);
        assertThat(envelope.occurredAt().getNano() % 1_000_000).isZero();

        PaymentSucceededPayload payload = envelope.payload();
        assertThat(payload.orderId()).isEqualTo(orderId);
        assertThat(payload.paymentId()).isEqualTo(paymentId);
        assertThat(payload.amount().amount()).isEqualTo("578000.00");
        assertThat(payload.amount().currency()).isEqualTo("VND");
        assertThat(payload.providerTransactionId()).isEqualTo("MOCK-TXN-20261022-000001");
        assertThat(payload.paidAt()).isEqualTo(Instant.parse("2026-10-22T03:15:45.789Z"));
    }

    @Test
    void buildsFailedEnvelope() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        MessageEnvelope<PaymentFailedPayload> envelope = factory.paymentFailed(
                orderId, paymentId, new BigDecimal("99.90"), "PAYMENT_DECLINED",
                "Mock payment declined", PAID_AT, TRACE_ID);

        assertThat(envelope.eventType()).isEqualTo(MessageTypes.EVENT_PAYMENT_FAILED);
        PaymentFailedPayload payload = envelope.payload();
        assertThat(payload.amount().amount()).isEqualTo("99.90");
        assertThat(payload.failureCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(payload.failureMessage()).isEqualTo("Mock payment declined");
        assertThat(payload.failedAt()).isEqualTo(Instant.parse("2026-10-22T03:15:45.789Z"));
    }

    @Test
    void buildsRefundedEnvelope() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        MessageEnvelope<PaymentRefundedPayload> envelope = factory.paymentRefunded(
                orderId, paymentId, refundId, new BigDecimal("578000.00"), PAID_AT, TRACE_ID);

        assertThat(envelope.eventType()).isEqualTo(MessageTypes.EVENT_PAYMENT_REFUNDED);
        PaymentRefundedPayload payload = envelope.payload();
        assertThat(payload.refundId()).isEqualTo(refundId);
        assertThat(payload.amount().amount()).isEqualTo("578000.00");
        assertThat(payload.refundedAt()).isEqualTo(Instant.parse("2026-10-22T03:15:45.789Z"));
    }

    @Test
    void eachCallGetsANewEventId() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        MessageEnvelope<PaymentSucceededPayload> first = factory.paymentSucceeded(
                orderId, paymentId, new BigDecimal("10.00"), "MOCK-TXN-20261022-000001", PAID_AT, TRACE_ID);
        MessageEnvelope<PaymentSucceededPayload> second = factory.paymentSucceeded(
                orderId, paymentId, new BigDecimal("10.00"), "MOCK-TXN-20261022-000001", PAID_AT, TRACE_ID);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void generatesTraceIdWhenMissingOrMalformed() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        MessageEnvelope<PaymentSucceededPayload> missing = factory.paymentSucceeded(
                orderId, paymentId, new BigDecimal("10.00"), "MOCK-TXN-20261022-000001", PAID_AT, null);
        MessageEnvelope<PaymentSucceededPayload> malformed = factory.paymentSucceeded(
                orderId, paymentId, new BigDecimal("10.00"), "MOCK-TXN-20261022-000001", PAID_AT, "NOT-A-TRACE");

        assertThat(missing.traceId()).matches("^[0-9a-f]{32}$");
        assertThat(malformed.traceId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void serializesTimestampsWithExactlyThreeFractionDigitsAndZ() throws Exception {
        MessageEnvelope<PaymentSucceededPayload> envelope = factory.paymentSucceeded(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("578000.00"),
                "MOCK-TXN-20261022-000001", PAID_AT, TRACE_ID);

        JsonNode json = contractMapper.readTree(contractMapper.writeValueAsString(envelope));

        assertThat(json.path("occurredAt").asText())
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
        assertThat(json.path("payload").path("paidAt").asText()).isEqualTo("2026-10-22T03:15:45.789Z");
        assertThat(json.path("payload").path("amount").path("amount").asText()).isEqualTo("578000.00");
        assertThat(json.path("payload").path("amount").path("currency").asText()).isEqualTo("VND");
    }

    @Test
    void moneyKeepsTwoFractionDigitsWithoutSilentRounding() {
        assertThat(Money.vnd(new BigDecimal("0.5")).amount()).isEqualTo("0.50");
        assertThat(Money.vnd(new BigDecimal("1234567890")).amount()).isEqualTo("1234567890.00");
        assertThat(Money.vnd(new BigDecimal("12.34")).amount()).isEqualTo("12.34");
    }

    private static ObjectMapper contractMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().instantContractCustomizer().customize(builder);
        return builder.build();
    }
}
