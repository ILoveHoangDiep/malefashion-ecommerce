package com.malefashion.order.support;

import java.util.UUID;

/**
 * JSON stubs cho cart-coupon-service và catalog-service trong WireMock. Giá cart cố tình khác giá
 * catalog để test chứng minh order lấy giá từ catalog.
 */
public final class UpstreamFixtures {

    public static final UUID CART_ID =
            UUID.fromString("2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e");
    public static final String CART_PRICE = "299000.00";
    public static final String CATALOG_PRICE = "275000.00";
    public static final String PRODUCT_ID = "a9a46d2d-3802-5696-a45b-640da2d18386";

    private UpstreamFixtures() {
    }

    public static String cart(UUID userId) {
        return cart(userId, CART_ID, CART_PRICE, 2);
    }

    public static String cart(UUID userId, UUID cartId, String unitPrice, int quantity) {
        return """
                {
                  "cartId": "%s",
                  "userId": "%s",
                  "currency": "VND",
                  "items": [
                    {
                      "variantId": "%s",
                      "sku": "MF-TEE-BLACK-M",
                      "productName": "Basic Tee",
                      "size": "M",
                      "color": "BLACK",
                      "imageUrl": "http://localhost:9000/malefashion-products/tee-black-m.jpg",
                      "quantity": %d,
                      "unitPrice": {"amount": "%s", "currency": "VND"},
                      "lineTotal": {"amount": "598000.00", "currency": "VND"}
                    }
                  ],
                  "coupon": {
                    "code": "SUMMER10",
                    "discountAmount": {"amount": "50000.00", "currency": "VND"}
                  },
                  "subtotal": {"amount": "598000.00", "currency": "VND"},
                  "discountAmount": {"amount": "50000.00", "currency": "VND"},
                  "totalAmount": {"amount": "548000.00", "currency": "VND"},
                  "updatedAt": "2026-10-22T03:14:00.000Z"
                }
                """.formatted(cartId, userId, OrderFixtures.VARIANT_ID, quantity, unitPrice);
    }

    public static String emptyCart(UUID userId) {
        return """
                {
                  "cartId": "%s",
                  "userId": "%s",
                  "currency": "VND",
                  "items": [],
                  "subtotal": {"amount": "0.00", "currency": "VND"},
                  "discountAmount": {"amount": "0.00", "currency": "VND"},
                  "totalAmount": {"amount": "0.00", "currency": "VND"},
                  "updatedAt": "2026-10-22T03:14:00.000Z"
                }
                """.formatted(CART_ID, userId);
    }

    public static String catalog() {
        return catalog(OrderFixtures.VARIANT_ID, CATALOG_PRICE, true);
    }

    public static String catalog(UUID variantId, String price, boolean active) {
        return """
                {
                  "variants": [
                    {
                      "variantId": "%s",
                      "sku": "MF-TEE-BLACK-M",
                      "productId": "%s",
                      "productName": "Basic Tee",
                      "price": {"amount": "%s", "currency": "VND"},
                      "attributes": {"size": "M", "color": "BLACK"},
                      "imageUrl": "http://localhost:9000/malefashion-products/tee-black-m.jpg",
                      "active": %s
                    }
                  ],
                  "missingIds": []
                }
                """.formatted(variantId, PRODUCT_ID, price, active);
    }

    public static String catalogMissing(UUID variantId) {
        return """
                {
                  "variants": [],
                  "missingIds": ["%s"]
                }
                """.formatted(variantId);
    }

    public static String checkoutBody() {
        return checkoutBody(CART_ID);
    }

    public static String checkoutBody(UUID cartId) {
        return """
                {
                  "cartId": "%s",
                  "couponCode": "SUMMER10",
                  "shippingAddress": {
                    "recipientName": "Nguyen Van An",
                    "phone": "0901234567",
                    "addressLine": "1 Dai Co Viet",
                    "ward": "Le Dai Hanh",
                    "district": "Hai Ba Trung",
                    "city": "Ha Noi"
                  },
                  "paymentMethod": "MOCK_CARD"
                }
                """.formatted(cartId);
    }

    public static String checkoutBodyWithScenario(String scenario) {
        return """
                {
                  "cartId": "%s",
                  "couponCode": "SUMMER10",
                  "shippingAddress": {
                    "recipientName": "Nguyen Van An",
                    "phone": "0901234567",
                    "addressLine": "1 Dai Co Viet",
                    "ward": "Le Dai Hanh",
                    "district": "Hai Ba Trung",
                    "city": "Ha Noi"
                  },
                  "paymentMethod": "MOCK_CARD",
                  "mockPaymentScenario": "%s"
                }
                """.formatted(CART_ID, scenario);
    }

    public static String cartPath(UUID userId) {
        return "/internal/v1/carts/" + userId;
    }
}
