package com.malefashion.cartcoupon.messaging;

import com.malefashion.cartcoupon.domain.entity.Coupon;
import com.malefashion.cartcoupon.domain.entity.CouponUsage;
import com.malefashion.cartcoupon.messaging.event.OrderConfirmedEvent;
import com.malefashion.cartcoupon.repository.CouponRepository;
import com.malefashion.cartcoupon.repository.CouponUsageRepository;
import com.malefashion.cartcoupon.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Lắng nghe sự kiện từ queue cart-coupon-service.events.
 *
 * <p>Routing keys được xử lý:</p>
 * <ul>
 *   <li>{@code order.confirmed} – trừ usedCount coupon + xóa cart Redis</li>
 *   <li>{@code order.cancelled} – chỉ cần xóa cart (nếu còn)</li>
 * </ul>
 *
 * <p>Queue, exchange, DLQ đã được khai báo bởi order-service khi khởi động.
 * Service này KHÔNG tự khai báo lại (theo contracts/README.md).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConfirmedConsumer {

    private final CartService cartService;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    @RabbitListener(queues = "cart-coupon-service.events")
    @Transactional
    public void handleOrderEvent(
            OrderConfirmedEvent event,
            @Header(value = "amqp_receivedRoutingKey", defaultValue = "") String routingKey) {

        log.info("Received event routingKey={} eventId={}", routingKey, event.eventId());

        switch (routingKey) {
            case "order.confirmed" -> handleOrderConfirmed(event);
            case "order.cancelled" -> handleOrderCancelled(event);
            default -> log.warn("Unknown routing key '{}', ignoring event eventId={}", routingKey, event.eventId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void handleOrderConfirmed(OrderConfirmedEvent event) {
        OrderConfirmedEvent.Payload payload = event.payload();
        if (payload == null) {
            log.error("order.confirmed event {} has null payload, skipping", event.eventId());
            return;
        }

        UUID orderId = payload.orderId();
        UUID userId  = payload.userId();
        String couponCode = payload.couponCode();

        log.info("Processing order.confirmed orderId={} userId={} couponCode={}", orderId, userId, couponCode);

        // ── 1. Ghi nhận coupon usage (idempotent theo orderId) ────────────────
        if (couponCode != null && !couponCode.isBlank()) {
            recordCouponUsage(couponCode, userId, orderId);
        }

        // ── 2. Xóa cart Redis ────────────────────────────────────────────────
        cartService.clearCart(userId);
        log.info("Cart cleared for userId={} after order.confirmed", userId);
    }

    private void handleOrderCancelled(OrderConfirmedEvent event) {
        OrderConfirmedEvent.Payload payload = event.payload();
        if (payload == null) return;

        UUID userId = payload.userId();
        log.info("Processing order.cancelled orderId={} userId={} – clearing cart", payload.orderId(), userId);
        cartService.clearCart(userId);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tăng usedCount của coupon và tạo bản ghi CouponUsage.
     * Idempotent: kiểm tra {@code orderId} trước khi ghi để tránh double-processing.
     */
    private void recordCouponUsage(String couponCode, UUID userId, UUID orderId) {
        // Idempotency guard: nếu orderId đã được ghi trước đó → bỏ qua
        if (couponUsageRepository.existsByOrderId(orderId)) {
            log.info("CouponUsage already recorded for orderId={}, skipping", orderId);
            return;
        }

        Optional<Coupon> couponOpt = couponRepository.findByCode(couponCode.trim().toUpperCase());
        if (couponOpt.isEmpty()) {
            log.warn("Coupon code '{}' not found in DB, cannot record usage", couponCode);
            return;
        }

        Coupon coupon = couponOpt.get();

        // Tăng usedCount
        int currentUsed = coupon.getUsedCount() != null ? coupon.getUsedCount() : 0;
        coupon.setUsedCount(currentUsed + 1);
        couponRepository.save(coupon);

        // Lưu usage record
        CouponUsage usage = CouponUsage.builder()
                .coupon(coupon)
                .userId(userId)
                .orderId(orderId)
                .build();
        couponUsageRepository.save(usage);

        log.info("CouponUsage recorded: coupon={} userId={} orderId={}", couponCode, userId, orderId);
    }
}
