package com.malefashion.inventory.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    private OutboxEvent(UUID id, String eventType, String routingKey, JsonNode payload, Instant now) {
        this.id = id;
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = now;
    }

    public static OutboxEvent pending(UUID id,
                                      String eventType,
                                      String routingKey,
                                      JsonNode payload,
                                      Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(routingKey, "routingKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new OutboxEvent(id, eventType, routingKey, payload, now);
    }

    public void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.publishedAt = now;
    }

    public void recordAttemptFailure() {
        this.attempts++;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
