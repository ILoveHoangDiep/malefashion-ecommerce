package com.malefashion.order.web;

import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.infrastructure.messaging.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Xác thực các header do gateway inject và dựng {@link RequestContext} cho mọi endpoint
 * {@code /api/v1/orders/**}.
 *
 * <p>Không log email, Authorization hay X-Internal-Api-Key. traceId luôn được đặt vào MDC trước khi
 * kiểm tra header để response lỗi vẫn mang đúng trace; MDC được dọn ở đầu và cuối mỗi request để
 * không rò rỉ giữa các request dùng lại thread.</p>
 */
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    public static final String HEADER_INTERNAL_API_KEY = "X-Internal-Api-Key";
    public static final String HEADER_CALLER_SERVICE = "X-Caller-Service";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    private static final Logger log = LoggerFactory.getLogger(RequestContextInterceptor.class);

    private static final String CHECKOUT_PATH = "/api/v1/orders/checkout";
    private static final Pattern CALLER_SERVICE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
    private static final Pattern ROLE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String MDC_USER_ID = "userId";

    private final byte[] expectedInternalApiKey;

    public RequestContextInterceptor(OrderProperties properties) {
        this.expectedInternalApiKey = properties.internalApiKey()
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        clearMdc();

        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (!TraceIds.isValid(traceId)) {
            traceId = TraceIds.newTraceId();
        }
        MDC.put(TraceIds.MDC_TRACE_ID, traceId);

        String providedKey = request.getHeader(HEADER_INTERNAL_API_KEY);
        if (providedKey == null || !MessageDigest.isEqual(expectedInternalApiKey,
                providedKey.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.invalidInternalApiKey();
        }

        String callerService = trimToNull(request.getHeader(HEADER_CALLER_SERVICE));
        if (callerService == null || !CALLER_SERVICE_PATTERN.matcher(callerService).matches()) {
            throw ApiException.missingCallerService();
        }

        UUID userId = parseUuid(request.getHeader(HEADER_USER_ID));
        if (userId == null) {
            throw ApiException.missingUserContext();
        }
        MDC.put(MDC_USER_ID, userId.toString());

        boolean checkout = "POST".equals(request.getMethod())
                && CHECKOUT_PATH.equals(request.getRequestURI());

        String userEmail = null;
        if (checkout) {
            userEmail = trimToNull(request.getHeader(HEADER_USER_EMAIL));
            if (userEmail == null || userEmail.length() > 320
                    || !EMAIL_PATTERN.matcher(userEmail).matches()) {
                throw ApiException.missingUserEmail();
            }
        }

        Set<String> roles = parseRoles(request.getHeader(HEADER_USER_ROLES));
        RequestContext context = new RequestContext(traceId, userId, userEmail, roles,
                callerService);
        if (checkout && !context.customerOrAdmin()) {
            throw ApiException.forbidden();
        }
        if (!roles.isEmpty() && !context.customerOrAdmin()) {
            // Role lạ (ví dụ STAFF) không được dùng cho API đơn hàng.
            throw ApiException.forbidden();
        }

        request.setAttribute(RequestContext.ATTRIBUTE, context);
        // Chỉ log định danh kỹ thuật; email/Authorization/internal key không bao giờ vào log.
        log.info("REST request method={} path={} caller={} userId={} traceId={}",
                request.getMethod(), request.getRequestURI(), callerService, userId, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        clearMdc();
    }

    private static Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String token : header.split(",", -1)) {
            String role = token.trim();
            if (role.isEmpty() || !ROLE_PATTERN.matcher(role).matches()) {
                throw ApiException.forbidden();
            }
            roles.add(role);
        }
        return roles;
    }

    private static UUID parseUuid(String value) {
        String candidate = trimToNull(value);
        if (candidate == null) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void clearMdc() {
        MDC.remove(TraceIds.MDC_TRACE_ID);
        MDC.remove(TraceIds.MDC_ORDER_ID);
        MDC.remove(TraceIds.MDC_EVENT_ID);
        MDC.remove(MDC_USER_ID);
    }
}
