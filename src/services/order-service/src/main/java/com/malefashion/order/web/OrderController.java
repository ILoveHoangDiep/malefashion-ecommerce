package com.malefashion.order.web;

import com.malefashion.order.application.CheckoutOutcome;
import com.malefashion.order.application.CheckoutService;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.infrastructure.persistence.OrderRepository;
import com.malefashion.order.web.dto.CheckoutRequest;
import com.malefashion.order.web.dto.OrderPageResponse;
import com.malefashion.order.web.dto.OrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ba endpoint Orders theo order-api.yaml. Header danh tính và internal key do
 * {@link RequestContextInterceptor} kiểm tra trước khi vào controller.
 */
@RestController
@RequestMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT = "createdAt,desc";

    private final CheckoutService checkoutService;
    private final OrderRepository orderRepository;
    private final OrderResponseMapper responseMapper;

    public OrderController(CheckoutService checkoutService,
                           OrderRepository orderRepository,
                           OrderResponseMapper responseMapper) {
        this.checkoutService = checkoutService;
        this.orderRepository = orderRepository;
        this.responseMapper = responseMapper;
    }

    @PostMapping(path = "/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request,
                                                  @RequestHeader(value = IDEMPOTENCY_HEADER,
                                                          required = false) String idempotencyKey,
                                                  HttpServletRequest httpRequest) {
        RequestContext context = RequestContext.current(httpRequest);
        UUID key = parseIdempotencyKey(idempotencyKey);
        CheckoutOutcome outcome = checkoutService.checkout(request, context, key);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(responseMapper.toResponse(outcome.order(), context.admin()));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable("orderId") UUID orderId,
                                  HttpServletRequest httpRequest) {
        RequestContext context = RequestContext.current(httpRequest);
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(ApiException::orderNotFound);
        if (!context.admin() && !order.getUserId().equals(context.userId())) {
            // Không lộ sự tồn tại của đơn người khác.
            throw ApiException.orderNotFound();
        }
        return responseMapper.toResponse(order, context.admin());
    }

    @GetMapping
    public OrderPageResponse listOrders(@RequestParam(value = "page", required = false) Integer page,
                                        @RequestParam(value = "size", required = false) Integer size,
                                        @RequestParam(value = "sort", required = false) String sort,
                                        @RequestParam(value = "status", required = false) String status,
                                        HttpServletRequest httpRequest) {
        RequestContext context = RequestContext.current(httpRequest);
        int pageNumber = validatePage(page);
        int pageSize = validateSize(size);
        validateSort(sort);
        OrderStatus statusFilter = parseStatus(status);

        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        // OpenAPI chỉ mô tả lịch sử đơn của chính X-User-Id và không có tham số lọc userId, nên
        // ADMIN cũng chỉ thấy đơn của mình; quyền xem mọi đơn chỉ áp dụng cho GET /{orderId}.
        Page<Order> orders = statusFilter == null
                ? orderRepository.findByUserId(context.userId(), pageable)
                : orderRepository.findByUserIdAndStatus(context.userId(), statusFilter, pageable);

        List<UUID> ids = orders.getContent().stream().map(Order::getId).toList();
        Map<UUID, Order> withItems = new LinkedHashMap<>();
        for (Order order : orderRepository.findAllWithItemsByIdIn(ids)) {
            withItems.put(order.getId(), order);
        }
        List<OrderResponse> content = ids.stream()
                .map(withItems::get)
                .filter(Objects::nonNull)
                .map(order -> responseMapper.toResponse(order, context.admin()))
                .toList();

        return new OrderPageResponse(content, orders.getNumber(), orders.getSize(),
                orders.getTotalElements(), orders.getTotalPages());
    }

    private static UUID parseIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.missingIdempotencyKey();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                    "Idempotency-Key phải là UUID.",
                    List.of(new ProblemResponse.ErrorDetail(IDEMPOTENCY_HEADER, "must be a UUID")));
        }
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                    "page phải >= 0.",
                    List.of(new ProblemResponse.ErrorDetail("page", "must be greater than or equal to 0")));
        }
        return page;
    }

    private static int validateSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                    "size phải >= 1.",
                    List.of(new ProblemResponse.ErrorDetail("size", "must be greater than or equal to 1")));
        }
        if (size > MAX_PAGE_SIZE) {
            throw ApiException.invalidPageSize();
        }
        return size;
    }

    private static void validateSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return;
        }
        if (!DEFAULT_SORT.equals(sort.trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                    "Chỉ hỗ trợ sort=createdAt,desc.",
                    List.of(new ProblemResponse.ErrorDetail("sort", "must be createdAt,desc")));
        }
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                    "status không hợp lệ.",
                    List.of(new ProblemResponse.ErrorDetail("status", "invalid status")));
        }
    }
}
