package com.malefashion.order.infrastructure.persistence;

import com.malefashion.order.domain.support.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @EmbeddedId
    private ProcessedMessageId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
        // for JPA
    }

    private ProcessedMessage(ProcessedMessageId id, Instant processedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.processedAt = Instants.millis(Objects.requireNonNull(processedAt,
                "processedAt must not be null"));
    }

    public static ProcessedMessage of(String consumer, UUID messageId, Instant processedAt) {
        return new ProcessedMessage(new ProcessedMessageId(consumer, messageId), processedAt);
    }

    public ProcessedMessageId getId() {
        return id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
