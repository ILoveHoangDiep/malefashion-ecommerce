package com.malefashion.order.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProcessedMessageId implements Serializable {

    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    protected ProcessedMessageId() {
        // for JPA
    }

    public ProcessedMessageId(String consumer, UUID messageId) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.messageId = Objects.requireNonNull(messageId, "messageId must not be null");
    }

    public String getConsumer() {
        return consumer;
    }

    public UUID getMessageId() {
        return messageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedMessageId that)) {
            return false;
        }
        return Objects.equals(consumer, that.consumer)
                && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumer, messageId);
    }
}
