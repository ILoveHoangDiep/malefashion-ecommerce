package com.malefashion.order.integration;

import com.malefashion.order.support.IntegrationTestBase;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm tra các ràng buộc thật trong PostgreSQL, không dựa vào validate của JPA.
 */
class OrderSchemaConstraintIT extends IntegrationTestBase {

    private static final UUID USER_ID = UUID.fromString("9a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID VARIANT_ID =
            UUID.fromString("52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5");
    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");

    private static final String INSERT_ORDER = """
            INSERT INTO orders (id, order_number, user_id, user_email, customer_name, status,
                                cancel_reason, pending_cancel_reason, saga_step, refund_state,
                                refund_command_event_id, refund_retry_count, refund_deadline_at,
                                currency, subtotal, discount_amount, shipping_fee, total_amount,
                                coupon_code, mock_payment_scenario, shipping_recipient_name,
                                shipping_phone, shipping_address_line, shipping_ward,
                                shipping_district, shipping_city, reservation_id,
                                reservation_expires_at, payment_id, paid_amount,
                                provider_transaction_id, refund_id, saga_deadline_at,
                                compensation_deadline_at, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO order_items (id, order_id, variant_id, sku, product_name, size, color,
                                     quantity, unit_price, line_total)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void rejectsDuplicateOrderNumber() {
        insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING", "AWAITING_RESERVATION",
                "NONE", null, 0, new BigDecimal("598000.00"));

        assertViolation("uq_orders_order_number", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING",
                        "AWAITING_RESERVATION", "NONE", null, 0,
                        new BigDecimal("598000.00")));
    }

    @Test
    void rejectsMalformedOrderNumber() {
        assertViolation("ck_orders_order_number", () ->
                insertOrder(UUID.randomUUID(), "ORDER-123", "PENDING", "AWAITING_RESERVATION",
                        "NONE", null, 0, new BigDecimal("598000.00")));
    }

    @Test
    void rejectsDuplicateUserAndIdempotencyKey() {
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, "MF-20261022-7K3Q9A", "PENDING", "AWAITING_RESERVATION",
                "NONE", null, 0, new BigDecimal("598000.00"));
        UUID idempotencyKey = UUID.randomUUID();
        insertIdempotency(orderId, idempotencyKey);

        assertViolation("uq_checkout_idempotency_user_key", () ->
                insertIdempotency(orderId, idempotencyKey));
    }

    @Test
    void rejectsUnknownStatus() {
        assertViolation("ck_orders_status", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "SHIPPED",
                        "AWAITING_RESERVATION", "NONE", null, 0,
                        new BigDecimal("598000.00")));
    }

    @Test
    void rejectsUnknownSagaStep() {
        assertViolation("ck_orders_saga_step", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING",
                        "AWAITING_SHIPPING", "NONE", null, 0,
                        new BigDecimal("598000.00")));
    }

    @Test
    void rejectsUnknownRefundState() {
        assertViolation("ck_orders_refund_state", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING",
                        "AWAITING_RESERVATION", "PARTIAL", null, 0,
                        new BigDecimal("598000.00")));
    }

    @Test
    void rejectsUnknownCancelReason() {
        assertViolation("ck_orders_cancel_reason", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "CANCELLED",
                        "DONE", "NONE", "FRAUD", 0, new BigDecimal("598000.00")));
    }

    @Test
    void rejectsNegativeRefundRetryCount() {
        assertViolation("ck_orders_refund_retry_count", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING",
                        "AWAITING_RESERVATION", "NONE", null, -1,
                        new BigDecimal("598000.00")));
    }

    @Test
    void rejectsNegativeCommitRetryCount() {
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, "MF-20261022-7K3Q9A", "PENDING", "AWAITING_COMMIT",
                "NONE", null, 0, new BigDecimal("598000.00"));

        assertViolation("ck_orders_commit_retry_count", () ->
                jdbcTemplate.update(
                        "UPDATE orders SET commit_retry_count = -1 WHERE id = ?", orderId));
    }

    @Test
    void createsPartialIndexForCommitDeadlineScan() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'orders'
                  AND indexname = 'idx_orders_commit_deadline'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsNegativeMoney() {
        assertViolation("ck_orders_subtotal", () ->
                insertOrder(UUID.randomUUID(), "MF-20261022-7K3Q9A", "PENDING",
                        "AWAITING_RESERVATION", "NONE", null, 0,
                        new BigDecimal("-0.01")));
    }

    @Test
    void rejectsItemQuantityBelowOne() {
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, "MF-20261022-7K3Q9A", "PENDING", "AWAITING_RESERVATION",
                "NONE", null, 0, new BigDecimal("598000.00"));

        assertViolation("ck_order_items_quantity", () ->
                jdbcTemplate.update(INSERT_ITEM, UUID.randomUUID(), orderId, VARIANT_ID,
                        "MF-TEE-BLACK-M", "Basic Tee", "M", "BLACK", 0,
                        new BigDecimal("299000.00"), new BigDecimal("0.00")));
    }

    @Test
    void rejectsNegativeItemUnitPrice() {
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, "MF-20261022-7K3Q9A", "PENDING", "AWAITING_RESERVATION",
                "NONE", null, 0, new BigDecimal("598000.00"));

        assertViolation("ck_order_items_unit_price", () ->
                jdbcTemplate.update(INSERT_ITEM, UUID.randomUUID(), orderId, VARIANT_ID,
                        "MF-TEE-BLACK-M", "Basic Tee", "M", "BLACK", 1,
                        new BigDecimal("-1.00"), new BigDecimal("0.00")));
    }

    @Test
    void rejectsItemWithoutExistingOrder() {
        assertViolation("fk_order_items_order", () ->
                jdbcTemplate.update(INSERT_ITEM, UUID.randomUUID(), UUID.randomUUID(),
                        VARIANT_ID, "MF-TEE-BLACK-M", "Basic Tee", "M", "BLACK", 1,
                        new BigDecimal("299000.00"), new BigDecimal("299000.00")));
    }

    private void insertOrder(UUID id,
                             String orderNumber,
                             String status,
                             String sagaStep,
                             String refundState,
                             String cancelReason,
                             int refundRetryCount,
                             BigDecimal subtotal) {
        // PostgreSQL JDBC không tự suy ra kiểu cho java.time.Instant khi dùng JdbcTemplate.
        Instant now = NOW.truncatedTo(ChronoUnit.MILLIS);
        Timestamp createdAt = Timestamp.from(now);
        Timestamp sagaDeadlineAt = Timestamp.from(now.plus(10, ChronoUnit.MINUTES));
        jdbcTemplate.update(INSERT_ORDER,
                id, orderNumber, USER_ID, "an@example.com", "Nguyen Van An",
                status, cancelReason, null, sagaStep, refundState,
                null, refundRetryCount, null,
                "VND", subtotal, new BigDecimal("0.00"), new BigDecimal("30000.00"),
                new BigDecimal("628000.00"),
                null, null, "Nguyen Van An", "0901234567", "1 Dai Co Viet", "Le Dai Hanh",
                "Hai Ba Trung", "Ha Noi", null,
                null, null, null,
                null, null, sagaDeadlineAt,
                null, 0L, createdAt, createdAt);
    }

    private void insertIdempotency(UUID orderId, UUID idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO checkout_idempotency
                    (id, user_id, idempotency_key, request_hash, order_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, idempotencyKey, "a".repeat(64), orderId,
                Timestamp.from(NOW));
    }

    private void assertViolation(String constraintName, ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }
}
