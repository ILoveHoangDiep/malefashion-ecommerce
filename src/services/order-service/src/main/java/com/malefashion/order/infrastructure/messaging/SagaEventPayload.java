package com.malefashion.order.infrastructure.messaging;

import com.malefashion.order.domain.saga.StockReleaseReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload đã kiểu hoá của 8 event saga nhận. Parser đảm bảo mọi field bắt buộc có mặt và đúng kiểu
 * trước khi tạo record; handler 2b chỉ còn switch theo kiểu payload.
 */
public sealed interface SagaEventPayload {

    record StockReserved(UUID reservationId, Instant expiresAt, List<ReservedItem> items)
            implements SagaEventPayload {
        public StockReserved {
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("items must not be empty");
            }
        }
    }

    record StockReservationFailed(ReservationFailureReason reason,
                                  List<FailedItem> failedItems,
                                  Instant failedAt) implements SagaEventPayload {
        public StockReservationFailed {
            Objects.requireNonNull(reason, "reason must not be null");
            failedItems = List.copyOf(failedItems);
            Objects.requireNonNull(failedAt, "failedAt must not be null");
        }
    }

    record StockCommitted(UUID reservationId, Instant committedAt) implements SagaEventPayload {
        public StockCommitted {
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(committedAt, "committedAt must not be null");
        }
    }

    record StockCommitFailed(UUID reservationId, Instant failedAt) implements SagaEventPayload {
        public StockCommitFailed {
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(failedAt, "failedAt must not be null");
        }
    }

    record StockReleased(UUID reservationId, StockReleaseReason reason, Instant releasedAt)
            implements SagaEventPayload {
        public StockReleased {
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(releasedAt, "releasedAt must not be null");
        }
    }

    record PaymentSucceeded(UUID paymentId, Money amount, String providerTransactionId, Instant paidAt)
            implements SagaEventPayload {
        public PaymentSucceeded {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(providerTransactionId, "providerTransactionId must not be null");
            Objects.requireNonNull(paidAt, "paidAt must not be null");
        }
    }

    record PaymentFailed(UUID paymentId, Money amount, PaymentFailureCode failureCode,
                         String failureMessage, Instant failedAt) implements SagaEventPayload {
        public PaymentFailed {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(failureCode, "failureCode must not be null");
            Objects.requireNonNull(failureMessage, "failureMessage must not be null");
            Objects.requireNonNull(failedAt, "failedAt must not be null");
        }
    }

    record PaymentRefunded(UUID paymentId, UUID refundId, Money amount, Instant refundedAt)
            implements SagaEventPayload {
        public PaymentRefunded {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(refundId, "refundId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(refundedAt, "refundedAt must not be null");
        }
    }

    record ReservedItem(UUID variantId, String sku, int quantity) {
        public ReservedItem {
            Objects.requireNonNull(variantId, "variantId must not be null");
            Objects.requireNonNull(sku, "sku must not be null");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1");
            }
        }
    }

    record FailedItem(UUID variantId, String sku, int requestedQuantity, int availableQuantity) {
        public FailedItem {
            Objects.requireNonNull(variantId, "variantId must not be null");
            Objects.requireNonNull(sku, "sku must not be null");
            if (requestedQuantity < 1 || availableQuantity < 0) {
                throw new IllegalArgumentException("invalid failed item quantities");
            }
        }
    }
}
