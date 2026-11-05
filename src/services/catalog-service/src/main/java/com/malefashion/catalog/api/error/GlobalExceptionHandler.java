package com.malefashion.catalog.api.error;

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
import java.util.NoSuchElementException;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BASE_TYPE_URL = "https://malefashion.local/errors/";

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

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiProblem> handleNotFoundException(
            NoSuchElementException ex, HttpServletRequest request) {

        String traceId = resolveTraceId(request);
        ApiProblem problem = new ApiProblem(
                BASE_TYPE_URL + "not-found",
                "Resource not found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                "NOT_FOUND",
                traceId,
                Instant.now(),
                null
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiProblem> handleBadRequestException(
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
