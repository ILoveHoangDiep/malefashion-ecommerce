package com.malefashion.order.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.UUID;

/**
 * Danh tính đã được gateway xác thực và traceId của request hiện tại. Email chỉ giữ để lưu snapshot
 * order; TUYỆT ĐỐI không đưa vào log.
 */
public record RequestContext(
        String traceId,
        UUID userId,
        String userEmail,
        Set<String> roles,
        String callerService
) {

    public static final String ATTRIBUTE = RequestContext.class.getName();
    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_ADMIN = "ADMIN";

    public RequestContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean admin() {
        return roles.contains(ROLE_ADMIN);
    }

    public boolean customerOrAdmin() {
        return roles.contains(ROLE_CUSTOMER) || admin();
    }

    public static RequestContext current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof RequestContext context) {
            return context;
        }
        throw new IllegalStateException("RequestContext was not initialized by the interceptor");
    }
}
