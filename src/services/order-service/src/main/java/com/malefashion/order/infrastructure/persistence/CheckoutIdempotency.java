package com.malefashion.order.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Một checkout thành công cho cặp (user_id, idempotency_key). Unique constraint nằm ở DB.
 */
@Entity
@Table(name = "checkout_idempotency")
public class CheckoutIdempotency {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CheckoutIdempotency() {
        // for JPA
    }

    private CheckoutIdempotency(UUID id,
                                UUID userId,
                                UUID idempotencyKey,
                                String requestHash,
                                UUID orderId,
                                Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey,
                "idempotencyKey must not be null");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null")
                .truncatedTo(ChronoUnit.MILLIS);
    }

    public static CheckoutIdempotency create(UUID id,
                                             UUID userId,
                                             UUID idempotencyKey,
                                             String requestHash,
                                             UUID orderId,
                                             Instant createdAt) {
        return new CheckoutIdempotency(id, userId, idempotencyKey, requestHash, orderId, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
