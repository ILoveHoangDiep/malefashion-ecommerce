package com.malefashion.order.domain.model;

import com.malefashion.order.domain.support.Instants;
import com.malefashion.order.domain.support.MoneyAmounts;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root của đơn hàng. Trạng thái công khai và trạng thái saga là hai trục riêng:
 * storefront chỉ thấy {@link OrderStatus}, còn orchestrator theo dõi {@link SagaStep}.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_number", nullable = false, updatable = false, length = 24)
    private String orderNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, updatable = false, length = 320)
    private String userEmail;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 40)
    private CancelReason cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_cancel_reason", length = 40)
    private CancelReason pendingCancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_step", nullable = false, length = 30)
    private SagaStep sagaStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_state", nullable = false, length = 20)
    private RefundState refundState;

    @Column(name = "refund_command_event_id")
    private UUID refundCommandEventId;

    @Column(name = "refund_retry_count", nullable = false)
    private int refundRetryCount;

    @Column(name = "refund_deadline_at")
    private Instant refundDeadlineAt;

    @Column(name = "commit_command_event_id")
    private UUID commitCommandEventId;

    @Column(name = "commit_retry_count", nullable = false)
    private int commitRetryCount;

    @Column(name = "commit_deadline_at")
    private Instant commitDeadlineAt;

    @Column(name = "commit_unconfirmed", nullable = false)
    private boolean commitUnconfirmed;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "mock_payment_scenario", length = 20)
    private String mockPaymentScenario;

    @Column(name = "shipping_recipient_name", nullable = false, length = 200)
    private String shippingRecipientName;

    @Column(name = "shipping_phone", nullable = false, length = 20)
    private String shippingPhone;

    @Column(name = "shipping_address_line", nullable = false, length = 300)
    private String shippingAddressLine;

    @Column(name = "shipping_ward", nullable = false, length = 100)
    private String shippingWard;

    @Column(name = "shipping_district", nullable = false, length = 100)
    private String shippingDistrict;

    @Column(name = "shipping_city", nullable = false, length = 100)
    private String shippingCity;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "paid_amount", precision = 18, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "saga_deadline_at")
    private Instant sagaDeadlineAt;

    @Column(name = "compensation_deadline_at")
    private Instant compensationDeadlineAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    private Order(UUID id,
                  String orderNumber,
                  UUID userId,
                  String userEmail,
                  ShippingSnapshot shipping,
                  MoneySnapshot money,
                  String couponCode,
                  String mockPaymentScenario,
                  Instant sagaDeadlineAt,
                  Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.userEmail = Objects.requireNonNull(userEmail, "userEmail must not be null");
        this.customerName = shipping.recipientName();
        this.status = OrderStatus.PENDING;
        this.sagaStep = SagaStep.AWAITING_RESERVATION;
        this.refundState = RefundState.NONE;
        this.refundRetryCount = 0;
        this.currency = MoneyAmounts.CURRENCY;
        this.subtotal = MoneyAmounts.scale(money.subtotal());
        this.discountAmount = MoneyAmounts.scale(money.discountAmount());
        this.shippingFee = MoneyAmounts.scale(money.shippingFee());
        this.totalAmount = MoneyAmounts.scale(money.totalAmount());
        this.couponCode = couponCode;
        this.mockPaymentScenario = mockPaymentScenario;
        this.shippingRecipientName = shipping.recipientName();
        this.shippingPhone = shipping.phone();
        this.shippingAddressLine = shipping.addressLine();
        this.shippingWard = shipping.ward();
        this.shippingDistrict = shipping.district();
        this.shippingCity = shipping.city();
        this.sagaDeadlineAt = Instants.millis(sagaDeadlineAt);
        Instant timestamp = Instants.millis(Objects.requireNonNull(now, "now must not be null"));
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public static Order create(UUID id,
                               String orderNumber,
                               UUID userId,
                               String userEmail,
                               ShippingSnapshot shipping,
                               MoneySnapshot money,
                               String couponCode,
                               String mockPaymentScenario,
                               Instant sagaDeadlineAt,
                               Instant now) {
        return new Order(id, orderNumber, userId, userEmail, shipping, money, couponCode,
                mockPaymentScenario, sagaDeadlineAt, now);
    }

    public OrderItem addItem(UUID variantId,
                             String sku,
                             String productName,
                             String size,
                             String color,
                             int quantity,
                             BigDecimal unitPrice) {
        BigDecimal lineTotal = MoneyAmounts.lineTotal(unitPrice, quantity);
        OrderItem item = new OrderItem(UUID.randomUUID(), this, variantId, sku, productName, size,
                color, quantity, MoneyAmounts.scale(unitPrice), lineTotal);
        items.add(item);
        return item;
    }

    public void applyReservation(UUID reservationId, Instant expiresAt, Instant now) {
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.reservationExpiresAt = Instants.millis(expiresAt);
        touch(now);
    }

    /**
     * Reservation đến muộn sau khi saga đã timeout: chỉ ghi nhận để gửi release, không đổi status/step.
     */
    public void recordLateReservation(UUID reservationId, Instant expiresAt, Instant now) {
        applyReservation(reservationId, expiresAt, now);
    }

    public void applyPayment(UUID paymentId, BigDecimal paidAmount, String providerTransactionId,
                             Instant now) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.paidAmount = MoneyAmounts.scale(paidAmount);
        this.providerTransactionId = Objects.requireNonNull(providerTransactionId,
                "providerTransactionId must not be null");
        touch(now);
    }

    public void moveToStep(SagaStep step, Instant now) {
        this.sagaStep = Objects.requireNonNull(step, "step must not be null");
        touch(now);
    }

    public void setPendingCancelReason(CancelReason reason, Instant now) {
        this.pendingCancelReason = Objects.requireNonNull(reason, "reason must not be null");
        touch(now);
    }

    public void setCompensationDeadline(Instant deadline, Instant now) {
        this.compensationDeadlineAt = Instants.millis(deadline);
        touch(now);
    }

    public void clearCompensationDeadline(Instant now) {
        this.compensationDeadlineAt = null;
        touch(now);
    }

    public void clearSagaDeadline(Instant now) {
        this.sagaDeadlineAt = null;
        touch(now);
    }

    public void cancel(CancelReason reason, Instant now) {
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = Objects.requireNonNull(reason, "reason must not be null");
        touch(now);
    }

    public void fail(CancelReason reason, Instant now) {
        this.status = OrderStatus.FAILED;
        this.cancelReason = Objects.requireNonNull(reason, "reason must not be null");
        touch(now);
    }

    /**
     * Bắt đầu chờ inventory.commit-stock. Event id được sinh đúng một lần và dùng lại cho mọi lần
     * gửi lại để inventory dedupe.
     */
    public void startCommit(Duration timeout, Instant now, UUID commandEventId) {
        this.commitCommandEventId = Objects.requireNonNull(commandEventId,
                "commandEventId must not be null");
        this.commitRetryCount = 0;
        this.commitDeadlineAt = Instants.millis(now.plus(timeout));
        this.commitUnconfirmed = false;
        touch(now);
    }

    public void increaseCommitRetryCount(Instant now) {
        this.commitRetryCount++;
        touch(now);
    }

    public void setCommitDeadline(Instant deadline, Instant now) {
        this.commitDeadlineAt = Instants.millis(deadline);
        touch(now);
    }

    public void clearCommitDeadline(Instant now) {
        this.commitDeadlineAt = null;
        touch(now);
    }

    /**
     * Hết lượt gửi lại commit: đơn thất bại nhưng KHÔNG tự hoàn tiền vì order-service không biết
     * inventory đã chốt kho hay chưa. cancel_reason để null; event đến muộn có thể tự hồi phục.
     */
    public void markCommitUnconfirmed(Instant now) {
        this.status = OrderStatus.FAILED;
        this.cancelReason = null;
        this.pendingCancelReason = null;
        this.sagaDeadlineAt = null;
        this.compensationDeadlineAt = null;
        this.commitDeadlineAt = null;
        this.sagaStep = SagaStep.DONE;
        this.commitUnconfirmed = true;
        touch(now);
    }

    public void clearCommitUnconfirmed(Instant now) {
        this.commitUnconfirmed = false;
        touch(now);
    }

    public void confirm(Instant now) {
        this.status = OrderStatus.CONFIRMED;
        this.cancelReason = null;
        this.pendingCancelReason = null;
        this.sagaDeadlineAt = null;
        this.compensationDeadlineAt = null;
        this.refundDeadlineAt = null;
        this.commitDeadlineAt = null;
        this.commitUnconfirmed = false;
        touch(now);
    }

    /**
     * Bắt đầu chờ payment.refunded. Event id được sinh đúng một lần và dùng lại cho mọi lần gửi lại.
     */
    public void startRefund(Duration timeout, Instant now, UUID commandEventId) {
        this.refundState = RefundState.REQUESTED;
        this.refundCommandEventId = Objects.requireNonNull(commandEventId,
                "commandEventId must not be null");
        this.refundRetryCount = 0;
        this.refundDeadlineAt = Instants.millis(now.plus(timeout));
        touch(now);
    }

    public void increaseRefundRetryCount(Instant now) {
        this.refundRetryCount++;
        touch(now);
    }

    public void setRefundDeadline(Instant deadline, Instant now) {
        this.refundDeadlineAt = Instants.millis(deadline);
        touch(now);
    }

    public void clearRefundDeadline(Instant now) {
        this.refundDeadlineAt = null;
        touch(now);
    }

    public void applyRefund(UUID refundId, Instant now) {
        this.refundState = RefundState.REFUNDED;
        this.refundId = Objects.requireNonNull(refundId, "refundId must not be null");
        this.refundDeadlineAt = null;
        touch(now);
    }

    public void markRefundFailed(Instant now) {
        this.refundState = RefundState.REFUND_FAILED;
        this.refundDeadlineAt = null;
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = Instants.millis(now);
    }

    @PrePersist
    void ensureTimestamps() {
        Instant now = Instants.millis(Instant.now());
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        createdAt = Instants.millis(createdAt);
        updatedAt = Instants.millis(updatedAt);
    }

    @PreUpdate
    void truncateUpdatedAt() {
        updatedAt = Instants.millis(updatedAt);
    }

    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getCustomerName() {
        return customerName;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public CancelReason getPendingCancelReason() {
        return pendingCancelReason;
    }

    public SagaStep getSagaStep() {
        return sagaStep;
    }

    public RefundState getRefundState() {
        return refundState;
    }

    public UUID getRefundCommandEventId() {
        return refundCommandEventId;
    }

    public int getRefundRetryCount() {
        return refundRetryCount;
    }

    public Instant getRefundDeadlineAt() {
        return refundDeadlineAt;
    }

    public UUID getCommitCommandEventId() {
        return commitCommandEventId;
    }

    public int getCommitRetryCount() {
        return commitRetryCount;
    }

    public Instant getCommitDeadlineAt() {
        return commitDeadlineAt;
    }

    public boolean isCommitUnconfirmed() {
        return commitUnconfirmed;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public String getMockPaymentScenario() {
        return mockPaymentScenario;
    }

    public String getShippingRecipientName() {
        return shippingRecipientName;
    }

    public String getShippingPhone() {
        return shippingPhone;
    }

    public String getShippingAddressLine() {
        return shippingAddressLine;
    }

    public String getShippingWard() {
        return shippingWard;
    }

    public String getShippingDistrict() {
        return shippingDistrict;
    }

    public String getShippingCity() {
        return shippingCity;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Instant getReservationExpiresAt() {
        return reservationExpiresAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public Instant getSagaDeadlineAt() {
        return sagaDeadlineAt;
    }

    public Instant getCompensationDeadlineAt() {
        return compensationDeadlineAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Không đưa email vào log/toString; email chỉ rời service qua event order.*.
     */
    @Override
    public String toString() {
        return "Order{"
                + "id=" + id
                + ", orderNumber='" + orderNumber + '\''
                + ", userId=" + userId
                + ", status=" + status
                + ", sagaStep=" + sagaStep
                + ", refundState=" + refundState
                + '}';
    }

    public record ShippingSnapshot(
            String recipientName,
            String phone,
            String addressLine,
            String ward,
            String district,
            String city
    ) {
        public ShippingSnapshot {
            Objects.requireNonNull(recipientName, "recipientName must not be null");
            Objects.requireNonNull(phone, "phone must not be null");
            Objects.requireNonNull(addressLine, "addressLine must not be null");
            Objects.requireNonNull(ward, "ward must not be null");
            Objects.requireNonNull(district, "district must not be null");
            Objects.requireNonNull(city, "city must not be null");
        }
    }

    public record MoneySnapshot(
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal shippingFee,
            BigDecimal totalAmount
    ) {
        public MoneySnapshot {
            Objects.requireNonNull(subtotal, "subtotal must not be null");
            Objects.requireNonNull(discountAmount, "discountAmount must not be null");
            Objects.requireNonNull(shippingFee, "shippingFee must not be null");
            Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        }
    }
}
