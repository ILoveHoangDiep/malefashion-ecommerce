package com.malefashion.inventory.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * An error that has a stable contract representation. Anything else is handled as an internal error
 * without leaking details.
 */
public class InventoryApiException extends RuntimeException {

    private static final String ERROR_BASE = "https://malefashion.local/errors/";

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<ProblemFieldError> errors;

    private InventoryApiException(HttpStatus status, String code, String title, String detail,
                                  List<ProblemFieldError> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = List.copyOf(errors);
    }

    public static InventoryApiException variantNotFound() {
        return new InventoryApiException(
                HttpStatus.NOT_FOUND,
                "VARIANT_NOT_FOUND",
                "Variant not found",
                "Không có tồn kho cho variant này.",
                List.of());
    }

    /**
     * Matches the contract example for {@code TOO_MANY_IDS}, which carries an empty {@code errors} array.
     */
    public static InventoryApiException tooManyIds() {
        return new InventoryApiException(
                HttpStatus.BAD_REQUEST,
                "TOO_MANY_IDS",
                "Too many ids",
                "Tối đa 50 ID mỗi lần.",
                List.of());
    }

    public static InventoryApiException validation(String field, String message) {
        return new InventoryApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Validation error",
                "Yêu cầu không hợp lệ.",
                List.of(new ProblemFieldError(field, message)));
    }

    /** One error type URI per code, shared by every producer of problem details. */
    public static String typeFor(String code) {
        return ERROR_BASE + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public List<ProblemFieldError> errors() {
        return errors;
    }
}
