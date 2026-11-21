package com.malefashion.order.integration;

import com.malefashion.order.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * event_id là eventId của envelope/AMQP messageId, KHÔNG unique: retry refund chèn dòng mới với
 * đúng eventId cũ. Chỉ id kỹ thuật của dòng là duy nhất.
 */
class OutboxEventSchemaIT extends IntegrationTestBase {

    private static final UUID AGGREGATE_ID =
            UUID.fromString("5d1c9b2a-8e4f-4a6b-b1c2-3d4e5f6a7b8c");
    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");
    private static final String PAYLOAD = """
            {"eventId":"0192f4c1-7d1a-7b3c-8e2f-2a4b6c8d0e1f","eventType":"payment.refund-payment"}
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_events
                (id, event_id, event_type, aggregate_id, exchange, routing_key, payload,
                 status, attempts, created_at, published_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, NULL)
            """;

    @Test
    void allowsTwoRowsWithSameEventId() {
        UUID eventId = UUID.randomUUID();
        insertOutbox(UUID.randomUUID(), eventId);
        insertOutbox(UUID.randomUUID(), eventId);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateRowId() {
        UUID rowId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertOutbox(rowId, eventId);

        assertThatThrownBy(() -> insertOutbox(rowId, eventId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_outbox_events");
    }

    @Test
    void indexesEventIdWithoutUniqueConstraint() {
        String indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'outbox_events' AND indexname = 'idx_outbox_events_event_id'
                """, String.class);

        assertThat(indexDefinition).contains("(event_id)");
        assertThat(indexDefinition).doesNotContain("UNIQUE");
    }

    @Test
    void hasOnlyPrimaryKeyAsUniqueIndex() {
        List<String> uniqueIndexes = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'outbox_events' AND indexdef LIKE '%UNIQUE%'
                """, String.class);

        assertThat(uniqueIndexes).hasSize(1);
        assertThat(uniqueIndexes.getFirst()).contains("(id)");
    }

    private void insertOutbox(UUID id, UUID eventId) {
        // PostgreSQL JDBC không tự suy ra kiểu cho java.time.Instant khi dùng JdbcTemplate.
        jdbcTemplate.update(INSERT_OUTBOX, id, eventId, "payment.refund-payment", AGGREGATE_ID,
                "malefashion.commands", "payment.refund-payment", PAYLOAD,
                java.sql.Timestamp.from(NOW));
    }
}
