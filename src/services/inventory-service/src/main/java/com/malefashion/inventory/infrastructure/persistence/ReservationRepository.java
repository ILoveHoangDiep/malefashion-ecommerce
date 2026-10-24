package com.malefashion.inventory.infrastructure.persistence;

import com.malefashion.inventory.domain.model.Reservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Reservation> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = "items")
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findWithItemsById(@Param("id") UUID id);
}
