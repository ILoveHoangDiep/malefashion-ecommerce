package com.malefashion.inventory.infrastructure.persistence;

import com.malefashion.inventory.domain.model.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Reservation> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = "items")
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findWithItemsById(@Param("id") UUID id);

    /**
     * Locks only the reservation row; items are loaded lazily inside the same transaction. A fetch join
     * combined with {@code FOR UPDATE} would also lock the collection rows and is rejected by some drivers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findWithItemsByIdForUpdate(@Param("id") UUID id);

    /**
     * Claims one expired reservation for this transaction. SKIP LOCKED keeps parallel job runs from
     * waiting on, or processing, the same row.
     */
    @Query(value = """
            SELECT id FROM stock_reservations
            WHERE status = 'RESERVED' AND expires_at < :now
            ORDER BY expires_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> findNextExpiredReservationIdForUpdate(@Param("now") Instant now);
}
