package com.malefashion.payment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần thanh toán của đơn hàng. Mỗi {@code orderId} chỉ có đúng một payment.
 *
 * <p>Tiền tệ luôn là VND theo contract; DB có CHECK constraint cho cả amount và currency.</p>
 */
@Entity
@Table(name = "payments")
public class Payment {

    public static final String CURRENCY_VND = "VND";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", length = 20)
    private PaymentScenario scenario;

    @Column(name = "provider_transaction_id", unique = true, length = 64)
    private String providerTransactionId;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID id,
                    UUID orderId,
                    BigDecimal amount,
                    PaymentStatus status,
                    PaymentScenario scenario,
                    Instant now) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = CURRENCY_VND;
        this.status = status;
        this.scenario = scenario;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Tạo payment ở trạng thái cuối cùng của lần xử lý. Nghiệp vụ quyết định status và các field
     * kết quả; entity chỉ giữ dữ liệu.
     */
    public static Payment create(UUID id,
                                 UUID orderId,
                                 BigDecimal amount,
                                 PaymentStatus status,
                                 PaymentScenario scenario,
                                 Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return new Payment(id, orderId, amount, status, scenario, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentScenario getScenario() {
        return scenario;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
