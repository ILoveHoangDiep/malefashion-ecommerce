package com.malefashion.payment.infrastructure.persistence;

import com.malefashion.payment.domain.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    /**
     * Mỗi order chỉ có một payment. Khi hai lệnh chạy song song, INSERT thua cuộc không ném lỗi mà
     * trả 0; caller SELECT lại để dùng dòng đã thắng (xem process-payment replay).
     */
    @Modifying
    @Query(value = """
            INSERT INTO payments (id, order_id, amount, currency, status, scenario,
                                  provider_transaction_id, failure_code, failure_message,
                                  paid_at, failed_at, created_at, updated_at, version)
            VALUES (:id, :orderId, :amount, :currency, :status, :scenario,
                    :providerTransactionId, :failureCode, :failureMessage,
                    :paidAt, :failedAt, :now, :now, 0)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("orderId") UUID orderId,
                       @Param("amount") BigDecimal amount,
                       @Param("currency") String currency,
                       @Param("status") String status,
                       @Param("scenario") String scenario,
                       @Param("providerTransactionId") String providerTransactionId,
                       @Param("failureCode") String failureCode,
                       @Param("failureMessage") String failureMessage,
                       @Param("paidAt") Instant paidAt,
                       @Param("failedAt") Instant failedAt,
                       @Param("now") Instant now);

    /**
     * Khóa dòng payment của order để refund không chạy song song với process-payment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") UUID orderId);
}
