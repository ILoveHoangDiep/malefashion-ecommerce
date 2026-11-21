package com.malefashion.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.domain.saga.StockReleaseReason;
import com.malefashion.order.domain.support.MoneyAmounts;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.FailedItem;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentRefunded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.PaymentSucceeded;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.ReservedItem;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockCommitted;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReleased;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReservationFailed;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload.StockReserved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parse 8 event của saga queue thành {@link InboundEvent}.
 *
 * <p>Type lấy từ header {@code type}, thiếu thì fallback body {@code eventType}. JSON hỏng, thiếu field
 * bắt buộc, lệch header/body, type không hỗ trợ, sai kiểu field hoặc sai enum đều là lỗi vĩnh viễn:
 * ném {@link AmqpRejectAndDontRequeueException} để retry policy bỏ qua và DLQ nhận ngay.</p>
 */
@Component
public class InboundEventParser {

    private static final Logger log = LoggerFactory.getLogger(InboundEventParser.class);

    private final ObjectMapper objectMapper;

    public InboundEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InboundEvent parse(Message message) {
        JsonNode root = readJson(message);
        String eventType = resolveType(message, root);
        if (!MessageTypes.INBOUND_EVENT_TYPES.contains(eventType)) {
            throw reject("unsupported event type: " + eventType, message);
        }

        UUID eventId = requiredUuid(root, "eventId", message);
        int eventVersion = requiredInt(root, "eventVersion", 1, message);
        Instant occurredAt = requiredInstant(root, "occurredAt", message);
        String producer = requiredText(root, "producer", message);
        UUID orderId = requiredUuid(root, "correlationId", message);
        String traceId = requiredText(root, "traceId", message);
        if (!TraceIds.isValid(traceId)) {
            throw reject("traceId is not 32 lowercase hex characters", message);
        }
        JsonNode payloadNode = requiredObject(root, "payload", message);
        UUID payloadOrderId = requiredUuid(payloadNode, "orderId", message);
        if (!payloadOrderId.equals(orderId)) {
            throw reject("payload.orderId " + payloadOrderId + " does not match correlationId "
                    + orderId, message);
        }

        SagaEventPayload payload = switch (eventType) {
            case MessageTypes.EVENT_STOCK_RESERVED -> parseStockReserved(payloadNode, message);
            case MessageTypes.EVENT_STOCK_RESERVATION_FAILED ->
                    parseStockReservationFailed(payloadNode, message);
            case MessageTypes.EVENT_STOCK_COMMITTED -> parseStockCommitted(payloadNode, message);
            case MessageTypes.EVENT_STOCK_COMMIT_FAILED ->
                    parseStockCommitFailed(payloadNode, message);
            case MessageTypes.EVENT_STOCK_RELEASED -> parseStockReleased(payloadNode, message);
            case MessageTypes.EVENT_PAYMENT_SUCCEEDED -> parsePaymentSucceeded(payloadNode, message);
            case MessageTypes.EVENT_PAYMENT_FAILED -> parsePaymentFailed(payloadNode, message);
            case MessageTypes.EVENT_PAYMENT_REFUNDED -> parsePaymentRefunded(payloadNode, message);
            default -> throw reject("unsupported event type: " + eventType, message);
        };

        return new InboundEvent(eventId, eventType, eventVersion, occurredAt, producer, orderId,
                traceId, payload);
    }

    private SagaEventPayload parseStockReserved(JsonNode payload, Message message) {
        UUID reservationId = requiredUuid(payload, "reservationId", message);
        Instant expiresAt = requiredInstant(payload, "expiresAt", message);
        JsonNode itemsNode = requiredArray(payload, "items", message);
        if (itemsNode.isEmpty()) {
            throw reject("payload.items must not be empty", message);
        }
        List<ReservedItem> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            requireObject(item, "payload.items[]", message);
            items.add(new ReservedItem(
                    requiredUuid(item, "variantId", message),
                    requiredText(item, "sku", message),
                    requiredInt(item, "quantity", 1, message)));
        }
        return new StockReserved(reservationId, expiresAt, items);
    }

    private SagaEventPayload parseStockReservationFailed(JsonNode payload, Message message) {
        ReservationFailureReason reason = requiredEnum(payload, "reason",
                ReservationFailureReason.class, message);
        JsonNode itemsNode = requiredArray(payload, "failedItems", message);
        if (itemsNode.isEmpty()) {
            throw reject("payload.failedItems must not be empty", message);
        }
        List<FailedItem> failedItems = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            requireObject(item, "payload.failedItems[]", message);
            failedItems.add(new FailedItem(
                    requiredUuid(item, "variantId", message),
                    requiredText(item, "sku", message),
                    requiredInt(item, "requestedQuantity", 1, message),
                    requiredInt(item, "availableQuantity", 0, message)));
        }
        Instant failedAt = requiredInstant(payload, "failedAt", message);
        return new StockReservationFailed(reason, failedItems, failedAt);
    }

    private SagaEventPayload parseStockCommitted(JsonNode payload, Message message) {
        UUID reservationId = requiredUuid(payload, "reservationId", message);
        Instant committedAt = requiredInstant(payload, "committedAt", message);
        return new StockCommitted(reservationId, committedAt);
    }

    private SagaEventPayload parseStockCommitFailed(JsonNode payload, Message message) {
        UUID reservationId = requiredUuid(payload, "reservationId", message);
        String reason = requiredText(payload, "reason", message);
        if (!"RESERVATION_EXPIRED".equals(reason)) {
            throw reject("unsupported stock-commit-failed reason: " + reason, message);
        }
        Instant failedAt = requiredInstant(payload, "failedAt", message);
        return new StockCommitFailed(reservationId, failedAt);
    }

    private SagaEventPayload parseStockReleased(JsonNode payload, Message message) {
        UUID reservationId = requiredUuid(payload, "reservationId", message);
        StockReleaseReason reason = requiredEnum(payload, "reason", StockReleaseReason.class,
                message);
        Instant releasedAt = requiredInstant(payload, "releasedAt", message);
        return new StockReleased(reservationId, reason, releasedAt);
    }

    private SagaEventPayload parsePaymentSucceeded(JsonNode payload, Message message) {
        UUID paymentId = requiredUuid(payload, "paymentId", message);
        Money amount = requiredMoney(payload, message);
        String providerTransactionId = requiredText(payload, "providerTransactionId", message);
        Instant paidAt = requiredInstant(payload, "paidAt", message);
        return new PaymentSucceeded(paymentId, amount, providerTransactionId, paidAt);
    }

    private SagaEventPayload parsePaymentFailed(JsonNode payload, Message message) {
        UUID paymentId = requiredUuid(payload, "paymentId", message);
        Money amount = requiredMoney(payload, message);
        PaymentFailureCode failureCode = requiredEnum(payload, "failureCode",
                PaymentFailureCode.class, message);
        String failureMessage = requiredText(payload, "failureMessage", message);
        Instant failedAt = requiredInstant(payload, "failedAt", message);
        return new PaymentFailed(paymentId, amount, failureCode, failureMessage, failedAt);
    }

    private SagaEventPayload parsePaymentRefunded(JsonNode payload, Message message) {
        UUID paymentId = requiredUuid(payload, "paymentId", message);
        UUID refundId = requiredUuid(payload, "refundId", message);
        Money amount = requiredMoney(payload, message);
        Instant refundedAt = requiredInstant(payload, "refundedAt", message);
        return new PaymentRefunded(paymentId, refundId, amount, refundedAt);
    }

    private JsonNode readJson(Message message) {
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw reject("message body must be a JSON object", message);
            }
            return root;
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
            throw reject("missing event type in both header and body", message);
        }
        return type;
    }

    private JsonNode requiredObject(JsonNode parent, String field, Message message) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw reject("missing required field " + field, message);
        }
        requireObject(node, field, message);
        return node;
    }

    private void requireObject(JsonNode node, String field, Message message) {
        if (!node.isObject()) {
            throw reject("field " + field + " must be an object", message);
        }
    }

    private JsonNode requiredArray(JsonNode parent, String field, Message message) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw reject("missing required field " + field, message);
        }
        if (!node.isArray()) {
            throw reject("field " + field + " must be an array", message);
        }
        return node;
    }

    private UUID requiredUuid(JsonNode parent, String field, Message message) {
        String value = requiredText(parent, field, message);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw reject("field " + field + " is not a UUID: " + value, message);
        }
    }

    private int requiredInt(JsonNode parent, String field, int minimum, Message message) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw reject("missing required field " + field, message);
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw reject("field " + field + " must be an integer", message);
        }
        int value = node.intValue();
        if (value < minimum) {
            throw reject("field " + field + " must be >= " + minimum, message);
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field, Message message) {
        String value = textOrNull(parent, field);
        if (value == null || value.isBlank()) {
            throw reject("missing or blank required field " + field, message);
        }
        return value;
    }

    private Instant requiredInstant(JsonNode parent, String field, Message message) {
        String value = requiredText(parent, field, message);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw reject("field " + field + " is not an ISO-8601 instant: " + value, message);
        }
    }

    private Money requiredMoney(JsonNode payload, Message message) {
        JsonNode node = requiredObject(payload, "amount", message);
        String amount = requiredText(node, "amount", message);
        if (!MoneyAmounts.AMOUNT_PATTERN.matcher(amount).matches()) {
            throw reject("amount is not a decimal string with two fraction digits: " + amount,
                    message);
        }
        String currency = requiredText(node, "currency", message);
        if (!MoneyAmounts.CURRENCY.equals(currency)) {
            throw reject("unsupported currency: " + currency, message);
        }
        return new Money(amount, currency);
    }

    private <E extends Enum<E>> E requiredEnum(JsonNode parent, String field, Class<E> type,
                                               Message message) {
        String value = requiredText(parent, field, message);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw reject("field " + field + " has unsupported value: " + value, message);
        }
    }

    private AmqpRejectAndDontRequeueException reject(String reason, Message message) {
        log.warn("Rejecting message {}: {}", message.getMessageProperties().getMessageId(), reason);
        return new AmqpRejectAndDontRequeueException(reason);
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() || !node.isTextual() ? null : node.textValue();
    }
}
