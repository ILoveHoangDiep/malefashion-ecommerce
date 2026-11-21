package com.malefashion.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Giữ tối đa {@code limit} dòng PENDING trong transaction hiện tại; instance khác bỏ qua chúng
     * nhờ SKIP LOCKED. Mỗi dòng được publish độc lập kể cả khi nhiều dòng cùng eventId.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForUpdate(@Param("limit") int limit);
}
