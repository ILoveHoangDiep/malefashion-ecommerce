package com.malefashion.order.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Lỗi nghiệp vụ đã có status + code theo contract. GlobalExceptionHandler chuyển thành
 * ProblemDetail (application/problem+json); không bao giờ lộ stack trace ra response.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;
    private final List<ProblemResponse.ErrorDetail> errors;

    public ApiException(HttpStatus status, String code, String title, String detail,
                        List<ProblemResponse.ErrorDetail> errors) {
        super(detail);
        this.status = status.value();
        this.code = code;
        this.title = title;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ApiException of(HttpStatus status, String code, String title, String detail) {
        return new ApiException(status, code, title, detail, List.of());
    }

    public static ApiException validation(String detail) {
        return of(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", detail);
    }

    public static ApiException invalidInternalApiKey() {
        return of(HttpStatus.UNAUTHORIZED, "INVALID_INTERNAL_API_KEY", "Invalid internal API key",
                "X-Internal-Api-Key không hợp lệ.");
    }

    public static ApiException missingCallerService() {
        return of(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                "Thiếu X-Caller-Service.");
    }

    public static ApiException missingUserContext() {
        return of(HttpStatus.UNAUTHORIZED, "MISSING_USER_CONTEXT", "Missing user context",
                "Thiếu X-User-Id.");
    }

    public static ApiException missingUserEmail() {
        return of(HttpStatus.UNAUTHORIZED, "MISSING_USER_EMAIL", "Missing user email",
                "Checkout cần X-User-Email.");
    }

    public static ApiException forbidden() {
        return of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden",
                "Chỉ CUSTOMER hoặc ADMIN được thực hiện thao tác này.");
    }

    public static ApiException missingIdempotencyKey() {
        return of(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "Missing idempotency key",
                "Thiếu Idempotency-Key.");
    }

    public static ApiException idempotencyKeyReused() {
        return of(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                "Idempotency-Key đã dùng cho một checkout khác.");
    }

    public static ApiException mockPaymentNotAllowed() {
        return of(HttpStatus.BAD_REQUEST, "MOCK_PAYMENT_NOT_ALLOWED",
                "Mock payment not allowed",
                "mockPaymentScenario chỉ được dùng ở profile demo/local.");
    }

    public static ApiException cartChanged() {
        return of(HttpStatus.CONFLICT, "CART_CHANGED", "Cart changed",
                "Giỏ đã thay đổi, vui lòng tải lại giỏ trước khi checkout.");
    }

    public static ApiException cartEmpty() {
        return of(HttpStatus.CONFLICT, "CART_EMPTY", "Cart is empty",
                "Giỏ hàng không có sản phẩm.");
    }

    public static ApiException variantUnavailable(String variantId) {
        return of(HttpStatus.CONFLICT, "VARIANT_UNAVAILABLE", "Variant unavailable",
                "Variant " + variantId + " không còn khả dụng.");
    }

    public static ApiException orderNotFound() {
        return of(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found",
                "Không tìm thấy đơn hàng.");
    }

    public static ApiException invalidPageSize() {
        return of(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE", "Invalid page size",
                "size phải nằm trong khoảng 1..100.");
    }

    public static ApiException serviceUnavailable(String service) {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Service unavailable",
                "Không gọi được " + service + ".");
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public List<ProblemResponse.ErrorDetail> getErrors() {
        return errors;
    }
}
