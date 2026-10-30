package com.malefashion.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_messages")
@IdClass(ProcessedMessageId.class)
public class ProcessedMessage {

    @Id
    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
        // for JPA
    }

    public ProcessedMessage(String consumer, UUID messageId, Instant processedAt) {
        this.consumer = consumer;
        this.messageId = messageId;
        this.processedAt = processedAt;
    }

    public String getConsumer() {
        return consumer;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
