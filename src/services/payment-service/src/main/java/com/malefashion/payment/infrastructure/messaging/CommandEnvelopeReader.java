package com.malefashion.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Đọc envelope command từ message AMQP.
 *
 * <p>Loại command lấy từ header {@code type}; thiếu thì fallback sang {@code eventType} trong body.
 * Header và body lệch nhau, thiếu cả hai, JSON hỏng hoặc thiếu field bắt buộc đều là lỗi vĩnh viễn:
 * ném {@link AmqpRejectAndDontRequeueException} để message đi thẳng DLQ.</p>
 */
@Component
public class CommandEnvelopeReader {

    private static final Logger log = LoggerFactory.getLogger(CommandEnvelopeReader.class);

    private final ObjectMapper objectMapper;

    public CommandEnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CommandEnvelope read(Message message) {
        JsonNode root = readJson(message);
        String commandType = resolveType(message, root);

        UUID eventId = requiredUuid(root, "eventId");
        UUID correlationId = requiredUuid(root, "correlationId");
        int eventVersion = root.path("eventVersion").asInt(1);
        Instant occurredAt = parseOccurredAt(root);
        String producer = textOrNull(root, "producer");
        String traceId = textOrNull(root, "traceId");

        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw reject("payload missing or not an object", message);
        }

        return new CommandEnvelope(eventId, commandType, eventVersion, occurredAt, producer,
                correlationId, traceId, payload);
    }

    public <T> T convert(JsonNode payloadNode, Class<T> type, Message message) {
        try {
            return objectMapper.treeToValue(payloadNode, type);
        } catch (IOException invalid) {
            throw reject("payload does not match " + type.getSimpleName() + ": " + invalid.getMessage(),
                    message);
        }
    }

    public AmqpRejectAndDontRequeueException reject(String reason, Message message) {
        log.warn("Rejecting message {}: {}", message.getMessageProperties().getMessageId(), reason);
        return new AmqpRejectAndDontRequeueException(reason);
    }

    private JsonNode readJson(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException malformed) {
            throw reject("cannot parse message body as JSON", message);
        }
    }

    private String resolveType(Message message, JsonNode root) {
        String headerType = message.getMessageProperties().getType();
        String bodyType = textOrNull(root, "eventType");

        if (headerType != null && bodyType != null && !headerType.equals(bodyType)) {
            throw reject("type header '" + headerType + "' does not match body eventType '"
                    + bodyType + "'", message);
        }
        String type = headerType != null ? headerType : bodyType;
        if (type == null) {
            throw reject("missing command type in both header and body", message);
        }
        return type;
    }

    private UUID requiredUuid(JsonNode root, String field) {
        String value = textOrNull(root, field);
        if (value == null) {
            throw new AmqpRejectAndDontRequeueException("missing required field " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException(
                    "field " + field + " is not a UUID: " + value, invalid);
        }
    }

    private Instant parseOccurredAt(JsonNode root) {
        String value = textOrNull(root, "occurredAt");
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new AmqpRejectAndDontRequeueException(
                    "occurredAt is not an ISO-8601 instant: " + value, invalid);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() || !node.isTextual() ? null : node.textValue();
    }

    public record CommandEnvelope(
            UUID eventId,
            String commandType,
            int eventVersion,
            Instant occurredAt,
            String producer,
            UUID correlationId,
            String traceId,
            JsonNode payload
    ) {
    }
}
