package com.malefashion.inventory.application;

import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.domain.model.Reservation;
import com.malefashion.inventory.domain.model.ReservationItem;
import com.malefashion.inventory.domain.model.ReservationStatus;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelopes;
import com.malefashion.inventory.infrastructure.messaging.event.ReleaseEventReason;
import com.malefashion.inventory.infrastructure.messaging.event.StockReleasedPayload;
import com.malefashion.inventory.infrastructure.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Returns stock of reservations whose TTL elapsed. Every reservation is handled in its own transaction,
 * and the candidate row is claimed with {@code FOR UPDATE SKIP LOCKED} so overlapping runs never process
 * the same reservation twice.
 */
@Service
public class ReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final TransactionTemplate transactionTemplate;
    private final InventoryProperties properties;
    private final ReservationRepository reservationRepository;
    private final StockItemLocker stockItemLocker;
    private final OutboxService outboxService;

    public ReservationExpiryService(PlatformTransactionManager transactionManager,
                                    InventoryProperties properties,
                                    ReservationRepository reservationRepository,
                                    StockItemLocker stockItemLocker,
                                    OutboxService outboxService) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.reservationRepository = reservationRepository;
        this.stockItemLocker = stockItemLocker;
        this.outboxService = outboxService;
    }

    @Scheduled(
            fixedDelayString = "${inventory.reservation.expiry-scan-interval-ms}",
            initialDelayString = "${inventory.reservation.expiry-initial-delay-ms}")
    public void expireDueReservations() {
        int maxPerRun = properties.reservation().maxExpiriesPerRun();
        for (int processed = 0; processed < maxPerRun; processed++) {
            Boolean handled = transactionTemplate.execute(status -> expireNextReservation());
            if (!Boolean.TRUE.equals(handled)) {
                return;
            }
        }
    }

    private boolean expireNextReservation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Optional<UUID> candidate = reservationRepository.findNextExpiredReservationIdForUpdate(now);
        if (candidate.isEmpty()) {
            return false;
        }

        Optional<Reservation> loaded = reservationRepository.findWithItemsByIdForUpdate(candidate.get());
        if (loaded.isEmpty() || loaded.get().getStatus() != ReservationStatus.RESERVED) {
            return true;
        }

        Reservation reservation = loaded.get();
        Map<UUID, StockItem> stockByVariant = stockItemLocker.lockByVariantId(
                reservation.getItems().stream().map(ReservationItem::getVariantId).toList());
        for (ReservationItem item : reservation.getItems()) {
            stockByVariant.get(item.getVariantId()).release(item.getQuantity(), now);
        }
        reservation.expire(now);

        StockReleasedPayload eventPayload = new StockReleasedPayload(
                reservation.getOrderId(),
                reservation.getId(),
                ReleaseEventReason.RESERVATION_EXPIRED,
                now);
        outboxService.append(MessageEnvelopes.create(
                MessageTypes.EVENT_STOCK_RELEASED,
                reservation.getOrderId(),
                null,
                eventPayload));
        log.info("Expired reservation {} for order {}; stock returned", reservation.getId(), reservation.getOrderId());
        return true;
    }
}
