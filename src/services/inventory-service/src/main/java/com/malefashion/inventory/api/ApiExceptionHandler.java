package com.malefashion.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Single place that turns failures of the public inventory API into RFC 9457 problem details.
 *
 * <p>Scoped to the {@code api} package so it cannot change the error shape of actuator or of
 * unmapped paths. Unexpected failures are logged with their stack trace but never echoed to the
 * client.</p>
 */
@RestControllerAdvice(basePackages = "com.malefashion.inventory.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(InventoryApiException.class)
    public ResponseEntity<ApiProblem> handleInventoryApi(InventoryApiException failure,
                                                         HttpServletRequest request) {
        log.debug("Rejecting {} {} with {}", request.getMethod(), request.getRequestURI(), failure.code());
        return respond(failure.status(), failure.code(), failure.title(), failure.getMessage(),
                failure.errors(), request);
    }

    /**
     * Defensive net for malformed inputs that never reach the controller's own parsing
     * (for example a future endpoint with typed parameters).
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiProblem> handleBinding(Exception failure, HttpServletRequest request) {
        String field = fieldOf(failure);
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation error",
                "Yêu cầu không hợp lệ.",
                List.of(new ProblemFieldError(field, "Invalid or missing value")), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception failure, HttpServletRequest request) {
        if (failure instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status != null) {
                log.warn("Request {} {} failed with {}", request.getMethod(), request.getRequestURI(), status);
                return respond(status, status.name(), status.getReasonPhrase(),
                        status.getReasonPhrase(), List.of(), request);
            }
        }
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), failure);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error",
                "Đã xảy ra lỗi không mong muốn.", List.of(), request);
    }

    private static ResponseEntity<ApiProblem> respond(HttpStatus status, String code, String title,
                                                      String detail, List<ProblemFieldError> errors,
                                                      HttpServletRequest request) {
        ApiProblem problem = new ApiProblem(
                InventoryApiException.typeFor(code),
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                traceId(request),
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                errors);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
    }

    private static String traceId(HttpServletRequest request) {
        if (request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE) instanceof String attribute) {
            return attribute;
        }
        String fromMdc = MDC.get(TraceIdFilter.MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String fieldOf(Exception failure) {
        if (failure instanceof MethodArgumentTypeMismatchException mismatch) {
            return mismatch.getName();
        }
        if (failure instanceof MissingServletRequestParameterException missing) {
            return missing.getParameterName();
        }
        return "request";
    }
}
