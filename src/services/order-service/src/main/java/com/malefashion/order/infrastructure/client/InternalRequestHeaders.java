package com.malefashion.order.infrastructure.client;

import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.web.RequestContext;
import com.malefashion.order.web.RequestContextInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Header bắt buộc cho mọi lời gọi {@code /internal/**}: internal key + caller + trace + user.
 */
@Component
public class InternalRequestHeaders {

    public static final String CALLER_SERVICE = "order-service";

    private final OrderProperties properties;

    public InternalRequestHeaders(OrderProperties properties) {
        this.properties = properties;
    }

    public void apply(HttpHeaders headers, RequestContext context) {
        headers.set(RequestContextInterceptor.HEADER_INTERNAL_API_KEY,
                properties.internalApiKey());
        headers.set(RequestContextInterceptor.HEADER_CALLER_SERVICE, CALLER_SERVICE);
        headers.set(RequestContextInterceptor.HEADER_TRACE_ID, context.traceId());
        headers.set(RequestContextInterceptor.HEADER_USER_ID, context.userId().toString());
    }
}
