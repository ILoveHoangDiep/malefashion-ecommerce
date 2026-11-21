package com.malefashion.order.support;

import com.malefashion.order.domain.model.Order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class OrderFixtures {

    public static final UUID ORDER_ID = UUID.fromString("5d1c9b2a-8e4f-4a6b-b1c2-3d4e5f6a7b8c");
    public static final UUID USER_ID = UUID.fromString("9a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    public static final String USER_EMAIL = "an@example.com";
    public static final String ORDER_NUMBER = "MF-20261022-7K3Q9A";
    public static final UUID RESERVATION_ID =
            UUID.fromString("7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d");
    public static final UUID PAYMENT_ID =
            UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e");
    public static final UUID REFUND_ID =
            UUID.fromString("e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b");
    public static final UUID VARIANT_ID =
            UUID.fromString("52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5");
    public static final String PROVIDER_TRANSACTION_ID = "MOCK-TXN-20261022-000001";

    public static final BigDecimal SUBTOTAL = new BigDecimal("598000.00");
    public static final BigDecimal DISCOUNT = new BigDecimal("50000.00");
    public static final BigDecimal SHIPPING_FEE = new BigDecimal("30000.00");
    public static final BigDecimal TOTAL = new BigDecimal("578000.00");

    private OrderFixtures() {
    }

    public static Order newOrder(Instant now) {
        return newOrder(now, now.plus(Duration.ofMinutes(10)));
    }

    public static Order newOrder(Instant now, Instant sagaDeadlineAt) {
        return newOrder(ORDER_ID, ORDER_NUMBER, now, sagaDeadlineAt);
    }

    /**
     * Fixture nhiều order trong cùng một test: id và orderNumber phải khác nhau.
     */
    public static Order newOrder(UUID orderId, String orderNumber, Instant now,
                                 Instant sagaDeadlineAt) {
        Order order = Order.create(
                orderId,
                orderNumber,
                USER_ID,
                USER_EMAIL,
                new Order.ShippingSnapshot(
                        "Nguyen Van An",
                        "0901234567",
                        "1 Dai Co Viet",
                        "Le Dai Hanh",
                        "Hai Ba Trung",
                        "Ha Noi"),
                new Order.MoneySnapshot(SUBTOTAL, DISCOUNT, SHIPPING_FEE, TOTAL),
                "SUMMER10",
                "SUCCESS",
                sagaDeadlineAt,
                now);
        order.addItem(VARIANT_ID, "MF-TEE-BLACK-M", "Basic Tee", "M", "BLACK", 2,
                new BigDecimal("299000.00"));
        return order;
    }
}
