package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationItem;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.command.ReleaseStockPayload;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.ReleaseEventReason;
import com.malefashion.inventory.infrastructure.messaging.event.StockReleasedPayload;
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
public class ReleaseStockService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseStockService.class);

    private final InventoryProperties properties;
    private final ReservationRepository reservationRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final StockItemLocker stockItemLocker;
    private final OutboxService outboxService;

    public ReleaseStockService(InventoryProperties properties,
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
    public void handle(MessageEnvelope<ReleaseStockPayload> command) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (!claim(command, now)) {
            return;
        }

        ReleaseStockPayload payload = command.payload();
        Optional<Reservation> found = reservationRepository.findWithItemsByIdForUpdate(payload.reservationId());
        if (found.isEmpty() || !found.get().getOrderId().equals(payload.orderId())) {
            log.warn("Release for order {} references unknown reservation {}; acknowledging without event",
                    payload.orderId(), payload.reservationId());
            return;
        }

        Reservation reservation = found.get();
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            log.warn("Reservation {} is {}; ignoring release command (no duplicate event)",
                    reservation.getId(), reservation.getStatus());
            return;
        }

        Map<UUID, StockItem> stockByVariant = stockItemLocker.lockByVariantId(
                reservation.getItems().stream().map(ReservationItem::getVariantId).toList());
        for (ReservationItem item : reservation.getItems()) {
            stockByVariant.get(item.getVariantId()).release(item.getQuantity(), now);
        }
        reservation.release(now);

        StockReleasedPayload eventPayload = new StockReleasedPayload(
                reservation.getOrderId(),
                reservation.getId(),
                ReleaseEventReason.valueOf(payload.reason().name()),
                now);
        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_RELEASED,
                reservation.getOrderId(),
                command.traceId(),
                eventPayload));
        log.info("Released reservation {} for order {} (reason {})",
                reservation.getId(), reservation.getOrderId(), payload.reason());
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
}
