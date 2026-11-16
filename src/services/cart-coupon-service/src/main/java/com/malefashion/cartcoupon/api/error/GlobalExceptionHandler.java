package com.malefashion.cartcoupon.api.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BASE_TYPE_URL = "https://malefashion.local/errors/";

    @ExceptionHandler(CartNotFoundException.class)
    public ResponseEntity<ApiProblem> handleCartNotFound(
            CartNotFoundException ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "cart-not-found",
                "Cart not found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "CART_NOT_FOUND",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(CartEmptyException.class)
    public ResponseEntity<ApiProblem> handleCartEmpty(
            CartEmptyException ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "cart-empty",
                "Cart is empty",
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "CART_EMPTY",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(CouponInvalidException.class)
    public ResponseEntity<ApiProblem> handleCouponInvalid(
            CouponInvalidException ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "coupon-invalid",
                "Coupon invalid",
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "COUPON_INVALID",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ProblemFieldError> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(new ProblemFieldError(fieldError.getField(), fieldError.getDefaultMessage()));
        }

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "validation-error",
                "Validation failed",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "VALIDATION_ERROR",
                traceId,
                Instant.now(),
                errors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiProblem> handleBadRequest(
            IllegalArgumentException ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "bad-request",
                "Bad Request",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "BAD_REQUEST",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleGenericException(
            Exception ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "internal-error",
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred",
                request.getRequestURI(),
                "INTERNAL_SERVER_ERROR",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request != null ? request.getHeader("X-Trace-Id") : null;
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
