package com.malefashion.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.inventory.application.CommitStockService;
import com.malefashion.inventory.application.ReleaseStockService;
import com.malefashion.inventory.application.ReserveStockService;
import com.malefashion.inventory.infrastructure.messaging.command.CommitStockPayload;
import com.malefashion.inventory.infrastructure.messaging.command.ReleaseStockPayload;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Consumes the saga commands of this service. The command type comes from the AMQP {@code type} header,
 * with the body's {@code eventType} as a fallback; a disagreement between the two is a permanent error.
 */
@Component
public class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

    private final ObjectMapper objectMapper;
    private final ReserveStockService reserveStockService;
    private final CommitStockService commitStockService;
    private final ReleaseStockService releaseStockService;

    public InventoryCommandListener(ObjectMapper objectMapper,
                                    ReserveStockService reserveStockService,
                                    CommitStockService commitStockService,
                                    ReleaseStockService releaseStockService) {
        this.objectMapper = objectMapper;
        this.reserveStockService = reserveStockService;
        this.commitStockService = commitStockService;
        this.releaseStockService = releaseStockService;
    }

    @RabbitListener(queues = MessagingTopology.INVENTORY_COMMANDS_QUEUE)
    public void onCommand(Message message) {
        JsonNode root = readJson(message);
        String type = resolveType(message, root);

        UUID eventId = requiredUuid(root, "eventId");
        UUID correlationId = requiredUuid(root, "correlationId");
        int eventVersion = root.path("eventVersion").asInt(1);
        Instant occurredAt = parseOccurredAt(root);
        String producer = textOrNull(root, "producer");
        String traceId = textOrNull(root, "traceId");

        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw reject("payload missing or not an object", message);
        }

        switch (type) {
            case MessageTypes.COMMAND_RESERVE_STOCK -> {
                ReserveStockPayload payload = convert(payloadNode, ReserveStockPayload.class, message);
                validateReserve(payload, message);
                reserveStockService.handle(envelope(eventId, type, eventVersion, occurredAt, producer,
                        correlationId, traceId, payload));
            }
            case MessageTypes.COMMAND_COMMIT_STOCK -> {
                CommitStockPayload payload = convert(payloadNode, CommitStockPayload.class, message);
                if (payload.orderId() == null || payload.reservationId() == null) {
                    throw reject("commit-stock requires orderId and reservationId", message);
                }
                commitStockService.handle(envelope(eventId, type, eventVersion, occurredAt, producer,
                        correlationId, traceId, payload));
            }
            case MessageTypes.COMMAND_RELEASE_STOCK -> {
                ReleaseStockPayload payload = convert(payloadNode, ReleaseStockPayload.class, message);
                if (payload.orderId() == null || payload.reservationId() == null || payload.reason() == null) {
                    throw reject("release-stock requires orderId, reservationId and reason", message);
                }
                releaseStockService.handle(envelope(eventId, type, eventVersion, occurredAt, producer,
                        correlationId, traceId, payload));
            }
            default -> throw reject("unsupported command type: " + type, message);
        }
    }

    private static <T> MessageEnvelope<T> envelope(UUID eventId, String type, int eventVersion,
                                                   Instant occurredAt, String producer, UUID correlationId,
                                                   String traceId, T payload) {
        return new MessageEnvelope<>(eventId, type, eventVersion, occurredAt, producer, correlationId,
                traceId, payload);
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
            throw reject("type header '" + headerType + "' does not match body eventType '" + bodyType + "'",
                    message);
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
            throw new AmqpRejectAndDontRequeueException("field " + field + " is not a UUID: " + value, invalid);
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
            throw new AmqpRejectAndDontRequeueException("occurredAt is not an ISO-8601 instant: " + value, invalid);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() || !node.isTextual() ? null : node.textValue();
    }

    private <T> T convert(JsonNode payloadNode, Class<T> type, Message message) {
        try {
            return objectMapper.treeToValue(payloadNode, type);
        } catch (IOException invalid) {
            throw reject("payload does not match " + type.getSimpleName() + ": " + invalid.getMessage(), message);
        }
    }

    private void validateReserve(ReserveStockPayload payload, Message message) {
        if (payload.orderId() == null) {
            throw reject("reserve-stock requires orderId", message);
        }
        if (payload.items() == null || payload.items().isEmpty()) {
            throw reject("reserve-stock requires at least one item", message);
        }
        for (ReserveStockPayload.Item item : payload.items()) {
            if (item == null || item.variantId() == null || item.sku() == null || item.sku().isBlank()
                    || item.quantity() < 1) {
                throw reject("every reserve-stock item needs variantId, sku and quantity >= 1", message);
            }
        }
    }

    private AmqpRejectAndDontRequeueException reject(String reason, Message message) {
        log.warn("Rejecting message {}: {}", message.getMessageProperties().getMessageId(), reason);
        return new AmqpRejectAndDontRequeueException(reason);
    }
}
