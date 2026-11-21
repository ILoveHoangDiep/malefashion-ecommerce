package com.malefashion.order.domain.saga;

import com.malefashion.order.domain.model.CancelReason;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.RefundState;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.domain.support.Instants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * State machine thuần của saga, không phụ thuộc Spring/DB. Mỗi method nhận dữ liệu event, quyết định
 * chuyển trạng thái và trả danh sách hành động; giai đoạn này chưa gửi message hay ghi outbox.
 *
 * <p>Mọi event lặp lại hoặc không áp dụng được đều trả outcome IGNORED, không ném exception.</p>
 */
public final class SagaStateMachine {

    private final SagaTiming timing;
    private final Clock clock;
    private final Supplier<UUID> eventIds;

    public SagaStateMachine(SagaTiming timing, Clock clock, Supplier<UUID> eventIds) {
        this.timing = Objects.requireNonNull(timing, "timing must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds must not be null");
    }

    /**
     * Dòng 1 và dòng 13 của bảng chuyển trạng thái.
     */
    public SagaTransition onStockReserved(Order order, UUID reservationId, Instant expiresAt) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_RESERVATION)) {
            order.applyReservation(reservationId, expiresAt, now);
            order.moveToStep(SagaStep.AWAITING_PAYMENT, now);
            return SagaTransition.applied(new SagaAction.SendProcessPayment());
        }
        if (order.getStatus() == OrderStatus.CANCELLED
                && order.getCancelReason() == CancelReason.SAGA_TIMEOUT
                && order.getSagaStep() == SagaStep.DONE) {
            // Reservation đến sau khi saga đã timeout và đơn đã hủy: giữ chỗ đang tồn tại nên phải nhả.
            order.recordLateReservation(reservationId, expiresAt, now);
            return SagaTransition.applied(
                    new SagaAction.SendReleaseStock(StockReleaseReason.SAGA_TIMEOUT));
        }
        if (order.getSagaStep() == SagaStep.AWAITING_PAYMENT
                || order.getSagaStep() == SagaStep.AWAITING_COMMIT
                || order.getSagaStep() == SagaStep.AWAITING_RELEASE
                || order.getSagaStep() == SagaStep.AWAITING_REFUND) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 2: không có reservation nên hủy trực tiếp, không gửi release.
     */
    public SagaTransition onStockReservationFailed(Order order) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_RESERVATION)) {
            order.cancel(CancelReason.OUT_OF_STOCK, now);
            order.clearSagaDeadline(now);
            order.moveToStep(SagaStep.DONE, now);
            return SagaTransition.applied(new SagaAction.EmitOrderCancelled());
        }
        if (order.getStatus() == OrderStatus.CANCELLED
                && order.getCancelReason() == CancelReason.OUT_OF_STOCK
                && order.getSagaStep() == SagaStep.DONE) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 3 và dòng 12: payment thành công bình thường, hoặc đến muộn sau khi đơn đã timeout.
     */
    public SagaTransition onPaymentSucceeded(Order order,
                                             UUID paymentId,
                                             BigDecimal paidAmount,
                                             String providerTransactionId) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_PAYMENT)) {
            order.applyPayment(paymentId, paidAmount, providerTransactionId, now);
            order.clearSagaDeadline(now);
            order.moveToStep(SagaStep.AWAITING_COMMIT, now);
            return SagaTransition.applied(new SagaAction.SendCommitStock());
        }
        if (isLatePaymentAfterTimeout(order)) {
            if (order.getRefundState() != RefundState.NONE) {
                return SagaTransition.ignoredDuplicate();
            }
            order.applyPayment(paymentId, paidAmount, providerTransactionId, now);
            order.startRefund(timing.compensationTimeout(), now, eventIds.get());
            return SagaTransition.applied(new SagaAction.SendRefund(false));
        }
        if (order.getSagaStep() == SagaStep.AWAITING_COMMIT) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 4: payment thất bại, chờ stock-released rồi mới hủy đơn.
     */
    public SagaTransition onPaymentFailed(Order order) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_PAYMENT)) {
            order.setPendingCancelReason(CancelReason.PAYMENT_FAILED, now);
            order.moveToStep(SagaStep.AWAITING_RELEASE, now);
            order.setCompensationDeadline(now.plus(timing.compensationTimeout()), now);
            return SagaTransition.applied(
                    new SagaAction.SendReleaseStock(StockReleaseReason.PAYMENT_FAILED));
        }
        if (order.getSagaStep() == SagaStep.AWAITING_RELEASE
                && order.getPendingCancelReason() == CancelReason.PAYMENT_FAILED) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 5 và dòng 6: hết hạn chờ reservation/payment.
     */
    public SagaTransition onSagaTimeout(Order order) {
        Instant now = now();
        if (!isDue(order.getSagaDeadlineAt(), now)) {
            return SagaTransition.notApplicable();
        }
        if (isPendingAwaiting(order, SagaStep.AWAITING_RESERVATION)) {
            order.cancel(CancelReason.SAGA_TIMEOUT, now);
            order.clearSagaDeadline(now);
            order.moveToStep(SagaStep.DONE, now);
            return SagaTransition.applied(new SagaAction.EmitOrderCancelled());
        }
        if (isPendingAwaiting(order, SagaStep.AWAITING_PAYMENT)) {
            order.setPendingCancelReason(CancelReason.SAGA_TIMEOUT, now);
            order.moveToStep(SagaStep.AWAITING_RELEASE, now);
            order.setCompensationDeadline(now.plus(timing.compensationTimeout()), now);
            order.clearSagaDeadline(now);
            return SagaTransition.applied(
                    new SagaAction.SendReleaseStock(StockReleaseReason.SAGA_TIMEOUT));
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 7.
     */
    public SagaTransition onStockCommitted(Order order) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_COMMIT)) {
            order.confirm(now);
            order.moveToStep(SagaStep.DONE, now);
            return SagaTransition.applied(new SagaAction.EmitOrderConfirmed());
        }
        if (order.getStatus() == OrderStatus.CONFIRMED && order.getSagaStep() == SagaStep.DONE) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 8: commit thất bại sau khi đã thu tiền, bắt đầu hoàn tiền.
     */
    public SagaTransition onStockCommitFailed(Order order) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_COMMIT)) {
            order.setPendingCancelReason(CancelReason.STOCK_COMMIT_FAILED, now);
            order.moveToStep(SagaStep.AWAITING_REFUND, now);
            order.startRefund(timing.compensationTimeout(), now, eventIds.get());
            return SagaTransition.applied(new SagaAction.SendRefund(false));
        }
        if (order.getSagaStep() == SagaStep.AWAITING_REFUND) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 9 và dòng 11.
     */
    public SagaTransition onStockReleased(Order order, StockReleaseReason reason) {
        Instant now = now();
        if (isPendingAwaiting(order, SagaStep.AWAITING_RELEASE)) {
            CancelReason cancelReason = order.getPendingCancelReason();
            if (cancelReason == null) {
                return SagaTransition.notApplicable();
            }
            order.cancel(cancelReason, now);
            order.clearCompensationDeadline(now);
            order.moveToStep(SagaStep.DONE, now);
            return SagaTransition.applied(new SagaAction.EmitOrderCancelled());
        }
        if ((order.getSagaStep() == SagaStep.AWAITING_PAYMENT
                || order.getSagaStep() == SagaStep.AWAITING_COMMIT)
                && reason == StockReleaseReason.RESERVATION_EXPIRED) {
            // TTL tự nhả reservation; chỉ ghi nhận, không đổi bước và không quyết định refund.
            return SagaTransition.applied();
        }
        if (order.getSagaStep() == SagaStep.AWAITING_RELEASE
                || (order.getStatus() == OrderStatus.CANCELLED
                && order.getSagaStep() == SagaStep.DONE)) {
            return SagaTransition.ignoredDuplicate();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 10, dòng 12 (phần refund đến sau) và dòng 14d.
     */
    public SagaTransition onPaymentRefunded(Order order, UUID refundId) {
        Instant now = now();
        if (order.getRefundState() == RefundState.REFUND_FAILED) {
            return new SagaTransition(SagaOutcome.IGNORED_NOT_APPLICABLE,
                    List.of(new SagaAction.LogWarn("REFUND_ALREADY_FAILED")));
        }
        if (order.getRefundState() == RefundState.REFUNDED) {
            return SagaTransition.ignoredDuplicate();
        }
        if (order.getRefundState() != RefundState.REQUESTED) {
            return SagaTransition.notApplicable();
        }
        if (isPendingAwaiting(order, SagaStep.AWAITING_REFUND)) {
            order.applyRefund(refundId, now);
            order.cancel(CancelReason.STOCK_COMMIT_FAILED, now);
            order.moveToStep(SagaStep.DONE, now);
            return SagaTransition.applied(new SagaAction.EmitOrderCancelled());
        }
        if (isLatePaymentAfterTimeout(order)) {
            // Đơn đã hủy (hoặc đang chờ release) vì timeout; refund chỉ để đóng sổ, không phát event nữa.
            order.applyRefund(refundId, now);
            return SagaTransition.applied();
        }
        return SagaTransition.notApplicable();
    }

    /**
     * Dòng 14a: quá hạn chờ stock-released thì hủy luôn để đơn không treo.
     */
    public SagaTransition onCompensationTimeout(Order order) {
        Instant now = now();
        if (!isPendingAwaiting(order, SagaStep.AWAITING_RELEASE)) {
            return SagaTransition.notApplicable();
        }
        if (!isDue(order.getCompensationDeadlineAt(), now)) {
            return SagaTransition.notApplicable();
        }
        CancelReason cancelReason = order.getPendingCancelReason();
        if (cancelReason == null) {
            return SagaTransition.notApplicable();
        }
        order.cancel(cancelReason, now);
        order.clearCompensationDeadline(now);
        order.moveToStep(SagaStep.DONE, now);
        return SagaTransition.applied(
                new SagaAction.EmitOrderCancelled(),
                new SagaAction.LogWarn("STOCK_RELEASE_TIMEOUT"));
    }

    /**
     * Dòng 14b và 14c: gửi lại refund-payment hoặc bỏ cuộc.
     */
    public SagaTransition onRefundTimeout(Order order) {
        Instant now = now();
        if (order.getRefundState() != RefundState.REQUESTED) {
            return SagaTransition.notApplicable();
        }
        if (!isDue(order.getRefundDeadlineAt(), now)) {
            return SagaTransition.notApplicable();
        }
        if (order.getRefundRetryCount() < timing.refundMaxRetries()) {
            order.increaseRefundRetryCount(now);
            // Retry dùng cùng eventId chỉ đảm bảo LỆNH KHÔNG BỊ MẤT (nếu lần gửi trước chưa tới
            // payment-service). payment-service dedupe theo eventId: nếu lệnh đã được xử lý rồi thì
            // bản gửi lại bị bỏ qua và KHÔNG có event mới; kết quả (payment.refunded) đã nằm trong
            // outbox của payment-service từ lần xử lý đầu và sẽ tới order-service theo cơ chế
            // at-least-once. Vì vậy retry KHÔNG phải cách để lấy lại một event đã mất. Nếu hết lượt
            // retry vẫn không thấy payment.refunded → refund_state=REFUND_FAILED, cần xử lý thủ công
            // (kiểm tra bảng payments/refunds và DLQ).
            order.setRefundDeadline(now.plus(retryDelay(order.getRefundRetryCount())), now);
            return SagaTransition.applied(new SagaAction.SendRefund(true));
        }
        order.markRefundFailed(now);
        if (isPendingAwaiting(order, SagaStep.AWAITING_REFUND)) {
            // Nhánh commit-failed: order đã thu tiền nhưng không hoàn được; không phát order.cancelled
            // vì contract không có reason dành cho trạng thái FAILED.
            order.fail(CancelReason.STOCK_COMMIT_FAILED, now);
            order.moveToStep(SagaStep.DONE, now);
        }
        return SagaTransition.applied(new SagaAction.LogError("REFUND_RETRY_EXHAUSTED"));
    }

    /**
     * Hạn chờ của lần gửi lại thứ k = backoff × multiplier^(k-1).
     */
    private Duration retryDelay(int retryNumber) {
        BigDecimal factor = BigDecimal.valueOf(timing.refundRetryBackoffMultiplier())
                .pow(retryNumber - 1);
        BigDecimal millis = BigDecimal.valueOf(timing.refundRetryBackoff().toMillis())
                .multiply(factor)
                .setScale(0, RoundingMode.HALF_UP);
        return Duration.ofMillis(millis.longValueExact());
    }

    private boolean isLatePaymentAfterTimeout(Order order) {
        return (order.getStatus() == OrderStatus.CANCELLED
                && order.getCancelReason() == CancelReason.SAGA_TIMEOUT)
                || (order.getSagaStep() == SagaStep.AWAITING_RELEASE
                && order.getPendingCancelReason() == CancelReason.SAGA_TIMEOUT);
    }

    private boolean isPendingAwaiting(Order order, SagaStep step) {
        return order.getStatus() == OrderStatus.PENDING && order.getSagaStep() == step;
    }

    private boolean isDue(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }

    private Instant now() {
        return Instants.millis(clock.instant());
    }
}
