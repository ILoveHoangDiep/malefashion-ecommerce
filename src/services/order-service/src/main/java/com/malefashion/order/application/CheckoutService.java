package com.malefashion.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.support.MoneyAmounts;
import com.malefashion.order.infrastructure.client.CartClient;
import com.malefashion.order.infrastructure.client.CartSnapshot;
import com.malefashion.order.infrastructure.client.CatalogClient;
import com.malefashion.order.infrastructure.client.UpstreamMoney;
import com.malefashion.order.infrastructure.client.VariantBatch;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotency;
import com.malefashion.order.infrastructure.persistence.CheckoutIdempotencyRepository;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.web.ApiException;
import com.malefashion.order.web.RequestContext;
import com.malefashion.order.web.dto.CheckoutRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Điều phối checkout: idempotency → cart (GET, có retry) → catalog (POST, không retry) → tính tiền
 * → ghi một transaction. Mọi HTTP call nằm NGOÀI transaction DB.
 */
@Service
public class CheckoutService {

    private static final String CART_SERVICE = "cart-coupon-service";
    private static final String CATALOG_SERVICE = "catalog-service";
    private static final Set<String> SIZES = Set.of("S", "M", "L");

    private final CheckoutWriter writer;
    private final CartClient cartClient;
    private final CatalogClient catalogClient;
    private final CheckoutIdempotencyRepository idempotencyRepository;
    private final OrderRepository orderRepository;
    private final OrderProperties properties;
    private final ObjectMapper objectMapper;

    public CheckoutService(CheckoutWriter writer,
                           CartClient cartClient,
                           CatalogClient catalogClient,
                           CheckoutIdempotencyRepository idempotencyRepository,
                           OrderRepository orderRepository,
                           OrderProperties properties,
                           ObjectMapper objectMapper) {
        this.writer = writer;
        this.cartClient = cartClient;
        this.catalogClient = catalogClient;
        this.idempotencyRepository = idempotencyRepository;
        this.orderRepository = orderRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CheckoutOutcome checkout(CheckoutRequest request, RequestContext context,
                                    UUID idempotencyKey) {
        if (request.mockPaymentScenario() != null && !properties.mockScenarioEnabled()) {
            throw ApiException.mockPaymentNotAllowed();
        }

        String requestHash = requestHash(request);
        Optional<CheckoutIdempotency> recorded = idempotencyRepository
                .findByUserIdAndIdempotencyKey(context.userId(), idempotencyKey);
        if (recorded.isPresent()) {
            return replay(recorded.get(), requestHash);
        }

        CartSnapshot cart = cartClient.fetch(context);
        validateCart(cart, request, context);
        Map<UUID, Integer> quantities = collectQuantities(cart);

        VariantBatch.Response catalog = catalogClient.fetch(List.copyOf(quantities.keySet()),
                context);
        List<CheckoutLine> lines = buildLines(quantities, catalog);
        Pricing pricing = price(lines, cart);

        Order.ShippingSnapshot shipping = new Order.ShippingSnapshot(
                request.shippingAddress().recipientName(),
                request.shippingAddress().phone(),
                request.shippingAddress().addressLine(),
                request.shippingAddress().ward(),
                request.shippingAddress().district(),
                request.shippingAddress().city());
        Order.MoneySnapshot money = new Order.MoneySnapshot(
                pricing.subtotal(), pricing.discount(), pricing.shipping(), pricing.total());
        String couponCode = cart.coupon() != null ? cart.coupon().code() : request.couponCode();
        String mockScenario = request.mockPaymentScenario() == null
                ? null : request.mockPaymentScenario().name();

        CheckoutDraft draft = new CheckoutDraft(context.userId(), context.userEmail(),
                idempotencyKey, requestHash, shipping, money, couponCode, mockScenario, lines);

        try {
            return new CheckoutOutcome(writer.create(draft), false);
        } catch (DataIntegrityViolationException conflict) {
            // Hai request cùng key chạy song song: bên thua đọc lại bản ghi thắng và replay.
            Optional<CheckoutIdempotency> winner = idempotencyRepository
                    .findByUserIdAndIdempotencyKey(context.userId(), idempotencyKey);
            if (winner.isEmpty()) {
                throw conflict;
            }
            return replay(winner.get(), requestHash);
        }
    }

    private CheckoutOutcome replay(CheckoutIdempotency record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw ApiException.idempotencyKeyReused();
        }
        Order order = orderRepository.findWithItemsById(record.getOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record points to a missing order"));
        return new CheckoutOutcome(order, true);
    }

    private void validateCart(CartSnapshot cart, CheckoutRequest request,
                              RequestContext context) {
        if (!context.userId().equals(cart.userId())
                || !"VND".equals(cart.currency())) {
            throw ApiException.serviceUnavailable(CART_SERVICE);
        }
        if (cart.cartId() == null || !cart.cartId().equals(request.cartId())) {
            throw ApiException.cartChanged();
        }
        if (cart.items().isEmpty()) {
            throw ApiException.cartEmpty();
        }
    }

    private Map<UUID, Integer> collectQuantities(CartSnapshot cart) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (CartSnapshot.CartItem item : cart.items()) {
            if (item.variantId() == null || item.quantity() == null || item.quantity() < 1) {
                throw ApiException.serviceUnavailable(CART_SERVICE);
            }
            quantities.merge(item.variantId(), item.quantity(), Integer::sum);
        }
        if (quantities.isEmpty()) {
            throw ApiException.cartEmpty();
        }
        return quantities;
    }

    private List<CheckoutLine> buildLines(Map<UUID, Integer> quantities,
                                          VariantBatch.Response catalog) {
        if (!catalog.missingIds().isEmpty()) {
            throw ApiException.variantUnavailable(catalog.missingIds().getFirst().toString());
        }
        Map<UUID, VariantBatch.VariantItem> variants = catalog.variants().stream()
                .filter(variant -> variant.variantId() != null)
                .collect(Collectors.toMap(VariantBatch.VariantItem::variantId,
                        Function.identity(), (first, second) -> first));

        List<CheckoutLine> lines = new ArrayList<>(quantities.size());
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            VariantBatch.VariantItem variant = variants.get(entry.getKey());
            if (variant == null || !Boolean.TRUE.equals(variant.active())) {
                throw ApiException.variantUnavailable(entry.getKey().toString());
            }
            String size = variant.attributes() == null ? null : variant.attributes().size();
            String color = variant.attributes() == null ? null : variant.attributes().color();
            if (variant.sku() == null || variant.productName() == null || color == null
                    || size == null || !SIZES.contains(size)) {
                throw ApiException.serviceUnavailable(CATALOG_SERVICE);
            }
            BigDecimal unitPrice = money(variant.price(), CATALOG_SERVICE);
            if (unitPrice.signum() < 0) {
                throw ApiException.serviceUnavailable(CATALOG_SERVICE);
            }
            lines.add(new CheckoutLine(entry.getKey(), variant.sku(), variant.productName(),
                    size, color, entry.getValue(), unitPrice));
        }
        return lines;
    }

    private Pricing price(List<CheckoutLine> lines, CartSnapshot cart) {
        BigDecimal subtotal = lines.stream()
                .map(line -> MoneyAmounts.lineTotal(line.unitPrice(), line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        subtotal = MoneyAmounts.scale(subtotal);

        BigDecimal discount = cart.discountAmount() == null
                ? BigDecimal.ZERO.setScale(2)
                : money(cart.discountAmount(), CART_SERVICE);
        if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw ApiException.serviceUnavailable(CART_SERVICE);
        }
        BigDecimal shipping = MoneyAmounts.scale(properties.shippingFee());
        BigDecimal total = MoneyAmounts.total(subtotal, discount, shipping);
        return new Pricing(subtotal, discount, shipping, total);
    }

    private BigDecimal money(UpstreamMoney value, String service) {
        if (value == null || value.amount() == null
                || !MoneyAmounts.AMOUNT_PATTERN.matcher(value.amount()).matches()
                || !MoneyAmounts.CURRENCY.equals(value.currency())) {
            throw ApiException.serviceUnavailable(service);
        }
        return new BigDecimal(value.amount());
    }

    private String requestHash(CheckoutRequest request) {
        try {
            byte[] canonical = objectMapper.writeValueAsString(request)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot hash checkout request", failure);
        }
    }

    private record Pricing(BigDecimal subtotal, BigDecimal discount, BigDecimal shipping,
                           BigDecimal total) {
    }
}
