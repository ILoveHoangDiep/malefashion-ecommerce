package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationItem;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.command.CommitStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.CommitFailureReason;
import com.malefashion.inventory.infrastructure.messaging.event.StockCommitFailedPayload;
import com.malefashion.inventory.infrastructure.messaging.event.StockCommittedPayload;
import com.malefashion.inventory.infrastructure.persistence.ProcessedMessageRepository;
import com.malefashion.inventory.infrastructure.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CommitStockService {

    private static final Logger log = LoggerFactory.getLogger(CommitStockService.class);

    private final InventoryProperties properties;
    private final ReservationRepository reservationRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final StockItemLocker stockItemLocker;
    private final OutboxService outboxService;

    public CommitStockService(InventoryProperties properties,
                              ReservationRepository reservationRepository,
                              ProcessedMessageRepository processedMessageRepository,
                              StockItemLocker stockItemLocker,
                              OutboxService outboxService) {
        this.properties = properties;
        this.reservationRepository = reservationRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.stockItemLocker = stockItemLocker;
        this.outboxService = outboxService;
    }

    @Transactional
    public void handle(MessageEnvelope<CommitStockPayload> command) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!claim(command, now)) {
            return;
        }

        CommitStockPayload payload = command.payload();
        Optional<Reservation> found = reservationRepository.findWithItemsByIdForUpdate(payload.reservationId());
        if (found.isEmpty() || !found.get().getOrderId().equals(payload.orderId())) {
            log.warn("Commit for order {} references unknown reservation {}; emitting stock-commit-failed",
                    payload.orderId(), payload.reservationId());
            emitCommitFailed(payload, command, now);
            return;
        }

        Reservation reservation = found.get();
        switch (reservation.getStatus()) {
            case COMMITTED -> {
                log.info("Reservation {} was already committed; replaying inventory.stock-committed",
                        reservation.getId());
                emitCommitted(reservation, reservation.getUpdatedAt(), command);
            }
            case RESERVED -> {
                if (reservation.isExpired(now)) {
                    log.warn("Reservation {} expired at {}; emitting stock-commit-failed",
                            reservation.getId(), reservation.getExpiresAt());
                    emitCommitFailed(payload, command, now);
                    return;
                }
                Map<UUID, StockItem> stockByVariant = stockItemLocker.lockByVariantId(
                        reservation.getItems().stream().map(ReservationItem::getVariantId).toList());
                for (ReservationItem item : reservation.getItems()) {
                    stockByVariant.get(item.getVariantId()).commit(item.getQuantity(), now);
                }
                reservation.commit(now);
                emitCommitted(reservation, now, command);
                log.info("Committed reservation {} for order {}", reservation.getId(), reservation.getOrderId());
            }
            case RELEASED, EXPIRED -> {
                log.warn("Reservation {} is {} and cannot be committed; emitting stock-commit-failed",
                        reservation.getId(), reservation.getStatus());
                emitCommitFailed(payload, command, now);
            }
        }
    }

    private boolean claim(MessageEnvelope<?> command, Instant now) {
        int inserted = processedMessageRepository.insertIfAbsent(
                properties.messaging().consumerName(), command.eventId(), now);
        if (inserted == 0) {
            log.info("Skipping duplicate command {} (type {})", command.eventId(), command.eventType());
            return false;
        }
        return true;
    }

    private void emitCommitted(Reservation reservation, Instant committedAt,
                               MessageEnvelope<CommitStockPayload> command) {
        StockCommittedPayload payload = new StockCommittedPayload(
                reservation.getOrderId(), reservation.getId(), committedAt);
        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_COMMITTED,
                reservation.getOrderId(),
                command.traceId(),
                payload));
    }

    private void emitCommitFailed(CommitStockPayload payload,
                                  MessageEnvelope<CommitStockPayload> command,
                                  Instant now) {
        StockCommitFailedPayload eventPayload = new StockCommitFailedPayload(
                payload.orderId(), payload.reservationId(), CommitFailureReason.RESERVATION_EXPIRED, now);
        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_COMMIT_FAILED,
                payload.orderId(),
                command.traceId(),
                eventPayload));
    }
}
