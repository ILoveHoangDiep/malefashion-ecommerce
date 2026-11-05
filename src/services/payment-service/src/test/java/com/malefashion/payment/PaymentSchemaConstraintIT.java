package com.malefashion.payment;

import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chứng minh các ràng buộc nghiệp vụ cốt lõi được PostgreSQL chặn ở tầng schema.
 */
class PaymentSchemaConstraintIT extends IntegrationTestBase {

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("0.00"), "VND", "SUCCEEDED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("-1.00"), "VND", "SUCCEEDED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount()).isZero();
    }

    @Test
    void rejectsDuplicateOrderId() {
        UUID orderId = UUID.randomUUID();
        insertPayment(UUID.randomUUID(), orderId, new BigDecimal("100000.00"), "VND", "SUCCEEDED");

        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), orderId,
                new BigDecimal("200000.00"), "VND", "FAILED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicatePaymentIdInRefunds() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        insertPayment(paymentId, orderId, new BigDecimal("578000.00"), "VND", "SUCCEEDED");

        insertRefund(UUID.randomUUID(), paymentId, orderId, new BigDecimal("578000.00"));

        assertThatThrownBy(() -> insertRefund(UUID.randomUUID(), paymentId, UUID.randomUUID(),
                new BigDecimal("578000.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer refunds = jdbcTemplate.queryForObject("SELECT count(*) FROM refunds", Integer.class);
        assertThat(refunds).isEqualTo(1);
    }

    @Test
    void rejectsCurrencyOtherThanVnd() {
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100000.00"), "USD", "SUCCEEDED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount()).isZero();
    }

    @Test
    void rejectsDuplicateMessageIdInProcessedMessages() {
        UUID messageId = UUID.randomUUID();
        insertProcessedMessage("payment-service", messageId);
        insertProcessedMessage("another-service", messageId);

        assertThatThrownBy(() -> insertProcessedMessage("payment-service", messageId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM processed_messages WHERE message_id = ?
                """, Integer.class, messageId);
        assertThat(rows).isEqualTo(2);
    }

    private void insertPayment(UUID id, UUID orderId, BigDecimal amount, String currency, String status) {
        jdbcTemplate.update("""
                INSERT INTO payments
                    (id, order_id, amount, currency, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, now(), now(), 0)
                """, id, orderId, amount, currency, status);
    }

    private void insertRefund(UUID id, UUID paymentId, UUID orderId, BigDecimal amount) {
        jdbcTemplate.update("""
                INSERT INTO refunds
                    (id, payment_id, order_id, amount, reason, refunded_at, created_at)
                VALUES (?, ?, ?, ?, 'STOCK_COMMIT_FAILED', now(), now())
                """, id, paymentId, orderId, amount);
    }

    private void insertProcessedMessage(String consumer, UUID messageId) {
        jdbcTemplate.update("""
                INSERT INTO processed_messages (consumer, message_id, processed_at)
                VALUES (?, ?, now())
                """, consumer, messageId);
    }

    private int paymentCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM payments", Integer.class);
        return count == null ? 0 : count;
    }
}
