package com.malefashion.inventory.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads the gateway's W3C trace id, replaces a missing or malformed value with a fresh one, puts it in
 * the MDC, and echoes it on the response. A missing header is never an error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(HEADER_NAME));
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveTraceId(String incoming) {
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (TRACE_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
