package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationItem;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.command.ReserveStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.ReservationFailureReason;
import com.malefashion.inventory.infrastructure.messaging.event.StockReservationFailedPayload;
import com.malefashion.inventory.infrastructure.messaging.event.StockReservationFailedPayload.FailedItem;
import com.malefashion.inventory.infrastructure.messaging.event.StockReservedPayload;
import com.malefashion.inventory.infrastructure.persistence.ProcessedMessageRepository;
import com.malefashion.inventory.infrastructure.persistence.ReservationRepository;
import com.malefashion.inventory.infrastructure.persistence.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one complete reservation attempt in one database transaction: inbox marker, business logic and
 * outbox event. It owns the {@link TransactionTemplate} so that {@link ReserveStockService} can start a
 * brand-new transaction for every retry.
 */
@Service
public class ReserveStockTransactionService {

    private static final Logger log = LoggerFactory.getLogger(ReserveStockTransactionService.class);

    private final TransactionTemplate transactionTemplate;
    private final InventoryProperties properties;
    private final ReservationRepository reservationRepository;
    private final StockItemRepository stockItemRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final StockItemLocker stockItemLocker;
    private final OutboxService outboxService;

    public ReserveStockTransactionService(PlatformTransactionManager transactionManager,
                                          InventoryProperties properties,
                                          ReservationRepository reservationRepository,
                                          StockItemRepository stockItemRepository,
                                          ProcessedMessageRepository processedMessageRepository,
                                          StockItemLocker stockItemLocker,
                                          OutboxService outboxService) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.reservationRepository = reservationRepository;
        this.stockItemRepository = stockItemRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.stockItemLocker = stockItemLocker;
        this.outboxService = outboxService;
    }

    public void attempt(MessageEnvelope<ReserveStockPayload> envelope) {
        transactionTemplate.executeWithoutResult(status -> attemptInTransaction(envelope));
    }

    /**
     * Called after every retry was exhausted. Runs in a fresh transaction because the attempts before it
     * were rolled back.
     */
    public void recordConcurrentFailure(MessageEnvelope<ReserveStockPayload> envelope) {
        transactionTemplate.executeWithoutResult(status -> recordConcurrentFailureInTransaction(envelope));
    }

    private void attemptInTransaction(MessageEnvelope<ReserveStockPayload> envelope) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!claim(envelope, now)) {
            return;
        }

        ReserveStockPayload payload = envelope.payload();
        List<RequestedItem> requestedItems = mergeDuplicateVariants(payload.items());

        Optional<Reservation> existing = reservationRepository.findByOrderId(payload.orderId());
        if (existing.isPresent()) {
            replayOrSkipExisting(existing.get(), envelope, now);
            return;
        }

        Map<UUID, StockItem> stockByVariant = stockItemLocker.lockByVariantId(
                requestedItems.stream().map(RequestedItem::variantId).toList());

        List<FailedItem> missingItems = requestedItems.stream()
                .filter(item -> !stockByVariant.containsKey(item.variantId()))
                .map(item -> new FailedItem(item.variantId(), item.sku(), item.quantity(), 0))
                .toList();
        if (!missingItems.isEmpty()) {
            emitReservationFailed(envelope, ReservationFailureReason.VARIANT_NOT_FOUND, missingItems, now);
            return;
        }

        List<FailedItem> insufficientItems = requestedItems.stream()
                .filter(item -> stockByVariant.get(item.variantId()).getAvailableQuantity() < item.quantity())
                .map(item -> new FailedItem(
                        item.variantId(),
                        item.sku(),
                        item.quantity(),
                        stockByVariant.get(item.variantId()).getAvailableQuantity()))
                .toList();
        if (!insufficientItems.isEmpty()) {
            emitReservationFailed(envelope, ReservationFailureReason.INSUFFICIENT_STOCK, insufficientItems, now);
            return;
        }

        List<ReservationItem> reservationItems = new ArrayList<>();
        for (RequestedItem item : requestedItems) {
            StockItem stockItem = stockByVariant.get(item.variantId());
            if (!stockItem.getSku().equals(item.sku())) {
                log.warn("SKU mismatch for variant {}: command said {}, stock says {}; using stock SKU",
                        item.variantId(), item.sku(), stockItem.getSku());
            }
            stockItem.reserve(item.quantity(), now);
            reservationItems.add(ReservationItem.create(item.variantId(), stockItem.getSku(), item.quantity()));
        }
        stockItemRepository.saveAll(stockByVariant.values());

        Instant expiresAt = now.plus(properties.reservation().ttl());
        Reservation reservation = Reservation.reserve(payload.orderId(), reservationItems, now, expiresAt);
        reservationRepository.save(reservation);

        emitStockReserved(reservation, envelope, now);
        log.info("Reserved stock for order {} as reservation {} (expires at {})",
                payload.orderId(), reservation.getId(), expiresAt);
    }

    private void recordConcurrentFailureInTransaction(MessageEnvelope<ReserveStockPayload> envelope) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!claim(envelope, now)) {
            return;
        }

        ReserveStockPayload payload = envelope.payload();
        Optional<Reservation> existing = reservationRepository.findByOrderId(payload.orderId());
        if (existing.isPresent()) {
            replayOrSkipExisting(existing.get(), envelope, now);
            return;
        }

        List<RequestedItem> requestedItems = mergeDuplicateVariants(payload.items());
        Map<UUID, StockItem> stockByVariant = stockItemRepository
                .findAllByVariantIdIn(requestedItems.stream().map(RequestedItem::variantId).toList())
                .stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getVariantId(), item), Map::putAll);

        List<FailedItem> failedItems = requestedItems.stream()
                .map(item -> {
                    StockItem stockItem = stockByVariant.get(item.variantId());
                    return new FailedItem(
                            item.variantId(),
                            stockItem != null ? stockItem.getSku() : item.sku(),
                            item.quantity(),
                            stockItem != null ? stockItem.getAvailableQuantity() : 0);
                })
                .toList();

        emitReservationFailed(envelope, ReservationFailureReason.CONCURRENT_MODIFICATION, failedItems, now);
        log.warn("Giving up reservation for order {} after {} attempts",
                payload.orderId(), properties.reservation().reserveMaxAttempts());
    }

    private void replayOrSkipExisting(Reservation reservation,
                                      MessageEnvelope<ReserveStockPayload> envelope,
                                      Instant now) {
        if (reservation.getStatus() == ReservationStatus.RESERVED) {
            log.info("Order {} already holds reservation {}; replaying inventory.stock-reserved",
                    reservation.getOrderId(), reservation.getId());
            emitStockReserved(reservation, envelope, now);
        } else {
            log.warn("Order {} already has reservation {} in status {}; nothing to do",
                    reservation.getOrderId(), reservation.getId(), reservation.getStatus());
        }
    }

    private boolean claim(MessageEnvelope<?> envelope, Instant now) {
        int inserted = processedMessageRepository.insertIfAbsent(
                properties.messaging().consumerName(), envelope.eventId(), now);
        if (inserted == 0) {
            log.info("Skipping duplicate command {} (type {})", envelope.eventId(), envelope.eventType());
            return false;
        }
        return true;
    }

    private void emitStockReserved(Reservation reservation,
                                   MessageEnvelope<ReserveStockPayload> command,
                                   Instant now) {
        List<StockReservedPayload.Item> items = reservation.getItems().stream()
                .map(item -> new StockReservedPayload.Item(item.getVariantId(), item.getSku(), item.getQuantity()))
                .toList();
        StockReservedPayload payload = new StockReservedPayload(
                reservation.getOrderId(), reservation.getId(), reservation.getExpiresAt(), items);

        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_RESERVED,
                reservation.getOrderId(),
                command.traceId(),
                payload));
    }

    private void emitReservationFailed(MessageEnvelope<ReserveStockPayload> command,
                                       ReservationFailureReason reason,
                                       List<FailedItem> failedItems,
                                       Instant now) {
        StockReservationFailedPayload payload = new StockReservationFailedPayload(
                command.payload().orderId(), reason, failedItems, now);

        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_RESERVATION_FAILED,
                command.payload().orderId(),
                command.traceId(),
                payload));
    }

    /**
     * Two command lines for the same variant are merged so that all-or-nothing checks and stock movement
     * see the real total while the stored reservation keeps one line per variant.
     */
    private static List<RequestedItem> mergeDuplicateVariants(List<ReserveStockPayload.Item> items) {
        Map<UUID, RequestedItem> byVariant = new LinkedHashMap<>();
        for (ReserveStockPayload.Item item : items) {
            byVariant.merge(
                    item.variantId(),
                    new RequestedItem(item.variantId(), item.sku(), item.quantity()),
                    (first, second) -> new RequestedItem(
                            first.variantId(),
                            first.sku(),
                            Math.addExact(first.quantity(), second.quantity())));
        }
        return List.copyOf(byVariant.values());
    }

    private record RequestedItem(UUID variantId, String sku, int quantity) {
    }
}
