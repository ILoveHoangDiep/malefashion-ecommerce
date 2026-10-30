package com.malefashion.inventory.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {

    /**
     * Inserts the inbox marker if absent. Returns 1 when this delivery won the race, 0 when it was already processed.
     * ON CONFLICT avoids marking the surrounding transaction rollback-only like an exception would.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_messages (consumer, message_id, processed_at)
            VALUES (:consumer, :messageId, :processedAt)
            ON CONFLICT (consumer, message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("consumer") String consumer,
                       @Param("messageId") UUID messageId,
                       @Param("processedAt") Instant processedAt);
}
