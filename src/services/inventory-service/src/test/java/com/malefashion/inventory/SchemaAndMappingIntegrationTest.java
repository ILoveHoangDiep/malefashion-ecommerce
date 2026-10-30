package com.malefashion.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationItem;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.StockReservedPayload;
import com.malefashion.inventory.infrastructure.persistence.OutboxEvent;
import com.malefashion.inventory.infrastructure.persistence.ProcessedMessageRepository;
import com.malefashion.inventory.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that Flyway V1 applies on a real PostgreSQL 16 and that Hibernate's mapping validates and
 * round-trips against that exact schema (the application context starts with ddl-auto=validate).
 */
class SchemaAndMappingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedMessageRepository processedMessageRepository;

    @Test
    void flywayAppliedV1AndCreatedEveryTable() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE success = true AND version = '1'
                """, Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('stock_items', 'stock_reservations', 'stock_reservation_items',
                                     'outbox_events', 'processed_messages')
                ORDER BY table_name
                """, String.class);
        assertThat(tables).containsExactly(
                "outbox_events", "processed_messages", "stock_items",
                "stock_reservation_items", "stock_reservations");

        List<String> checks = jdbcTemplate.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND constraint_type = 'CHECK'
                """, String.class);
        assertThat(checks).contains(
                "ck_stock_items_available_quantity",
                "ck_stock_items_reserved_quantity",
                "ck_stock_reservations_status",
                "ck_stock_reservation_items_quantity",
                "ck_outbox_events_status",
                "ck_outbox_events_attempts");
    }

    @Test
    void hibernateRoundTripsEntitiesAgainstTheFlywaySchema() {
        UUID variantId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            StockItem stockItem = StockItem.create(variantId, "MF-TEE-BLACK-M", 12, now);
            stockItemRepository.saveAndFlush(stockItem);

            Reservation reservation = Reservation.reserve(
                    UUID.randomUUID(),
                    List.of(ReservationItem.create(variantId, "MF-TEE-BLACK-M", 2)),
                    now,
                    now.plus(15, ChronoUnit.MINUTES));
            reservationRepository.saveAndFlush(reservation);

            StockReservedPayload reservedPayload = new StockReservedPayload(
                    reservation.getOrderId(),
                    reservation.getId(),
                    reservation.getExpiresAt(),
                    List.of(new StockReservedPayload.Item(variantId, "MF-TEE-BLACK-M", 2)));
            JsonNode payload = objectMapper.valueToTree(MessageEnvelopes.create(
                    MessageTypes.EVENT_STOCK_RESERVED,
                    reservation.getOrderId(),
                    "4bf92f3577b34da6a3ce929d0e0e4736",
                    reservedPayload));
            outboxEventRepository.saveAndFlush(OutboxEvent.pending(
                    UUID.randomUUID(), MessageTypes.EVENT_STOCK_RESERVED,
                    MessageTypes.EVENT_STOCK_RESERVED, payload, now));

            assertThat(processedMessageRepository.insertIfAbsent(
                    "inventory-service", UUID.randomUUID(), now)).isEqualTo(1);

            entityManager.flush();
            entityManager.clear();

            StockItem reloadedStock = stockItemRepository.findById(variantId).orElseThrow();
            assertThat(reloadedStock.getSku()).isEqualTo("MF-TEE-BLACK-M");
            assertThat(reloadedStock.getAvailableQuantity()).isEqualTo(12);
            assertThat(reloadedStock.getReservedQuantity()).isZero();
            assertThat(reloadedStock.getCreatedAt()).isEqualTo(now);

            Reservation reloadedReservation = reservationRepository
                    .findWithItemsById(reservation.getId()).orElseThrow();
            assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(reloadedReservation.getItems()).hasSize(1);
            assertThat(reloadedReservation.getItems().getFirst().getQuantity()).isEqualTo(2);

            OutboxEvent reloadedOutbox = outboxEventRepository.findAll().getFirst();
            assertThat(reloadedOutbox.getPayload().path("eventType").asText())
                    .isEqualTo("inventory.stock-reserved");
            assertThat(reloadedOutbox.getStatus().name()).isEqualTo("PENDING");
        });

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT sku, available_quantity, reserved_quantity, created_at
                FROM stock_items WHERE variant_id = ?
                """, variantId);
        assertThat(row.get("sku")).isEqualTo("MF-TEE-BLACK-M");
        assertThat(row.get("available_quantity")).isEqualTo(12);
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(now);
    }

    @Test
    void processedMessageInsertIsIdempotent() {
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(processedMessageRepository.insertIfAbsent("inventory-service", messageId, now))
                    .isEqualTo(1);
            assertThat(processedMessageRepository.insertIfAbsent("inventory-service", messageId, now))
                    .isZero();
        });

        assertThat(processedMessageCount(messageId)).isEqualTo(1);
    }

    @Test
    void databaseCheckConstraintRejectsNegativeStockAsBackup() {
        UUID variantId = UUID.randomUUID();
        stockItemRepository.saveAndFlush(StockItem.create(variantId, "MF-TEE-WHITE-S", 1, Instant.now()));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE stock_items SET available_quantity = -1 WHERE variant_id = ?", variantId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer available = jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM stock_items WHERE variant_id = ?", Integer.class, variantId);
        assertThat(available).isEqualTo(1);
    }
}
