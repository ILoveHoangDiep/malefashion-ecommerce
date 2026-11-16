package com.malefashion.inventory.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProcessedMessageId implements Serializable {

    private String consumer;
    private UUID messageId;

    protected ProcessedMessageId() {
        // for JPA
    }

    public ProcessedMessageId(String consumer, UUID messageId) {
        this.consumer = consumer;
        this.messageId = messageId;
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
        return Objects.equals(consumer, that.consumer) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumer, messageId);
    }
}
