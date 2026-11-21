package com.malefashion.order.web;

import com.malefashion.order.infrastructure.messaging.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * Chuyển mọi lỗi REST thành ProblemDetail đúng contract. Lỗi 500 chỉ log đầy đủ ở server, body trả
 * mã chung, không lộ chi tiết nội bộ hay stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String MDC_USER_ID = "userId";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemResponse> handleApi(ApiException failure,
                                                     HttpServletRequest request) {
        return problem(failure.getStatus(), failure.getCode(), failure.getTitle(),
                failure.getMessage(), failure.getErrors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleValidation(MethodArgumentNotValidException failure,
                                                            HttpServletRequest request) {
        List<ProblemResponse.ErrorDetail> errors = failure.getBindingResult().getFieldErrors()
                .stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new ProblemResponse.ErrorDetail(error.getField(),
                        error.getDefaultMessage()))
                .toList();
        if (errors.isEmpty()) {
            errors = failure.getBindingResult().getGlobalErrors().stream()
                    .map(error -> new ProblemResponse.ErrorDetail(error.getObjectName(),
                            error.getDefaultMessage()))
                    .toList();
        }
        return problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", "Validation failed",
                "Request không hợp lệ.", errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> handleUnreadable(HttpMessageNotReadableException failure,
                                                            HttpServletRequest request) {
        // Không dùng failure.getMessage(): Jackson có thể chèn nội dung body (chứa email) vào đó.
        return problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", "Validation failed",
                "Request body không hợp lệ.", List.of(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", "Validation failed",
                "Giá trị tham số không hợp lệ.",
                List.of(new ProblemResponse.ErrorDetail(failure.getName(), "invalid value")),
                request);
    }

    /**
     * Path không tồn tại (ví dụ /actuator/info khi chỉ mở health) không được rơi vào handler 500:
     * giữ nguyên 404 thay vì biến lỗi định tuyến thành lỗi hệ thống.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemResponse> handleNoResource(NoResourceFoundException failure,
                                                            HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "Not found",
                "Không tìm thấy tài nguyên.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleUnexpected(Exception failure,
                                                            HttpServletRequest request) {
        log.error("Unhandled REST error path={} traceId={} userId={}", request.getRequestURI(),
                currentTraceId(), MDC.get(MDC_USER_ID), failure);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "Internal error", "Đã xảy ra lỗi, vui lòng thử lại sau.", List.of(), request);
    }

    private ResponseEntity<ProblemResponse> problem(int status,
                                                     String code,
                                                     String title,
                                                     String detail,
                                                     List<ProblemResponse.ErrorDetail> errors,
                                                     HttpServletRequest request) {
        ProblemResponse body = new ProblemResponse(
                ProblemResponse.typeOf(code),
                title,
                status,
                detail,
                request.getRequestURI(),
                code,
                currentTraceId(),
                clock.instant(),
                errors);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIds.MDC_TRACE_ID);
        return TraceIds.isValid(traceId) ? traceId : TraceIds.newTraceId();
    }
}
