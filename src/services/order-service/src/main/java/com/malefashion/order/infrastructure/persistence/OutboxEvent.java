package com.malefashion.order.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.order.domain.support.Instants;
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

/**
 * Một dòng outbox độc lập. {@code id} là khóa kỹ thuật của dòng; {@code eventId} là eventId trong
 * envelope và là AMQP messageId. Hai dòng có thể cùng eventId (retry refund) nhưng khác id.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "exchange", nullable = false, length = 100, updatable = false)
    private String exchange;

    @Column(name = "routing_key", nullable = false, length = 100, updatable = false)
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

    private OutboxEvent(UUID id,
                        UUID eventId,
                        String eventType,
                        UUID aggregateId,
                        String exchange,
                        String routingKey,
                        JsonNode payload,
                        Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.exchange = Objects.requireNonNull(exchange, "exchange must not be null");
        this.routingKey = Objects.requireNonNull(routingKey, "routingKey must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instants.millis(Objects.requireNonNull(createdAt,
                "createdAt must not be null"));
    }

    public static OutboxEvent pending(UUID id,
                                      UUID eventId,
                                      String eventType,
                                      UUID aggregateId,
                                      String exchange,
                                      String routingKey,
                                      JsonNode payload,
                                      Instant createdAt) {
        return new OutboxEvent(id, eventId, eventType, aggregateId, exchange, routingKey, payload,
                createdAt);
    }

    public void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.publishedAt = Instants.millis(now);
    }

    public void recordAttemptFailure() {
        this.attempts++;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getExchange() {
        return exchange;
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
