package com.malefashion.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {

    /**
     * Dedupe inbox: insert marker nếu chưa có. Trả 0 nghĩa là message đã xử lý trước đó.
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
