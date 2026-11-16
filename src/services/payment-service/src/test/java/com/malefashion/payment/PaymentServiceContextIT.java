package com.malefashion.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.application.OutboxService;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentScenario;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.domain.model.Refund;
import com.malefashion.payment.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.payment.infrastructure.persistence.OutboxEvent;
import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chứng minh context load được, Flyway V1 chạy trên PostgreSQL 16 thật, và entity khớp schema
 * (ứng dụng chạy với {@code ddl-auto=validate}).
 */
class PaymentServiceContextIT extends IntegrationTestBase {

    @Autowired
    private OutboxService outboxService;

    @Test
    void flywayV1AppliedOnRealPostgres16() {
        String serverVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        assertThat(serverVersion).startsWith("16.");

        Integer applied = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE success = true AND version = '1'
                """, Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('payments', 'refunds', 'outbox_events', 'processed_messages')
                ORDER BY table_name
                """, String.class);
        assertThat(tables).containsExactly(
                "outbox_events", "payments", "processed_messages", "refunds");

        List<String> sequences = jdbcTemplate.queryForList("""
                SELECT sequence_name FROM information_schema.sequences
                WHERE sequence_schema = 'public'
                """, String.class);
        assertThat(sequences).contains("payment_provider_transaction_id_seq");
    }

    @Test
    void entitiesRoundTripAgainstFlywaySchema() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Payment payment = Payment.create(
                paymentId, orderId, new BigDecimal("578000.00"), PaymentStatus.SUCCEEDED,
                PaymentScenario.SUCCESS, now);
        paymentRepository.saveAndFlush(payment);

        Refund refund = Refund.create(
                UUID.randomUUID(), paymentId, orderId, new BigDecimal("578000.00"),
                "STOCK_COMMIT_FAILED", now);
        refundRepository.saveAndFlush(refund);

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getOrderId()).isEqualTo(orderId);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("578000.00");
        assertThat(reloaded.getCurrency()).isEqualTo("VND");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloaded.getScenario()).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(now);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
        assertThat(reloaded.getVersion()).isZero();

        Refund reloadedRefund = refundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(reloadedRefund.getOrderId()).isEqualTo(orderId);
        assertThat(reloadedRefund.getAmount()).isEqualByComparingTo("578000.00");
        assertThat(reloadedRefund.getReason()).isEqualTo("STOCK_COMMIT_FAILED");
        assertThat(reloadedRefund.getRefundedAt()).isEqualTo(now);
        assertThat(reloadedRefund.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void outboxServiceWritesEnvelopeWithContractTimestamps() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-22T03:15:45.789Z");

        outboxService.append(new MessageEnvelope<>(
                eventId,
                "payment.succeeded",
                1,
                occurredAt,
                "payment-service",
                orderId,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Map.of("orderId", orderId.toString())));

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getId()).isEqualTo(eventId);
        assertThat(event.getEventType()).isEqualTo("payment.succeeded");
        assertThat(event.getRoutingKey()).isEqualTo("payment.succeeded");
        assertThat(event.getStatus().name()).isEqualTo("PENDING");
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payload = event.getPayload();
        assertThat(payload.path("occurredAt").asText()).isEqualTo("2026-10-22T03:15:45.789Z");
        assertThat(payload.path("correlationId").asText()).isEqualTo(orderId.toString());
    }
}
