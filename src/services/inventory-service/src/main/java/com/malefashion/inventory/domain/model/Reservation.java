package com.malefashion.inventory.domain.model;

import com.malefashion.inventory.domain.exception.InvalidReservationStateException;
import com.malefashion.inventory.domain.exception.ReservationExpiredException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A time-limited hold on stock for one order.
 *
 * <p>State machine: {@code RESERVED -> COMMITTED | RELEASED | EXPIRED}.
 * Every other transition is rejected by the domain.</p>
 */
@Entity
@Table(name = "stock_reservations")
public class Reservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationItem> items = new ArrayList<>();

    protected Reservation() {
        // for JPA
    }

    private Reservation(UUID orderId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.status = ReservationStatus.RESERVED;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Reservation reserve(UUID orderId,
                                      List<ReservationItem> reservationItems,
                                      Instant now,
                                      Instant expiresAt) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(reservationItems, "items must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (reservationItems.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after now");
        }

        Reservation reservation = new Reservation(orderId, now, expiresAt);
        for (ReservationItem item : reservationItems) {
            Objects.requireNonNull(item, "item must not be null");
            reservation.addItem(item);
        }
        return reservation;
    }

    /**
     * Commits a reservation that is still RESERVED and not past its TTL.
     */
    public void commit(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireStatus(ReservationStatus.RESERVED, "commit");
        if (isExpired(now)) {
            throw new ReservationExpiredException(id);
        }
        status = ReservationStatus.COMMITTED;
        updatedAt = now;
    }

    /**
     * Releases a live reservation, e.g. because payment failed or the saga timed out.
     */
    public void release(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireStatus(ReservationStatus.RESERVED, "release");
        status = ReservationStatus.RELEASED;
        updatedAt = now;
    }

    /**
     * Marks an expired reservation. The caller is responsible for returning its stock.
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireStatus(ReservationStatus.RESERVED, "expire");
        status = ReservationStatus.EXPIRED;
        updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(expiresAt);
    }

    private void requireStatus(ReservationStatus expected, String action) {
        if (status != expected) {
            throw new InvalidReservationStateException(id, status, action);
        }
    }

    private void addItem(ReservationItem item) {
        item.attachTo(this);
        items.add(item);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<ReservationItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
