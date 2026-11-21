package com.malefashion.order.infrastructure.client;

import com.malefashion.order.web.ApiException;
import com.malefashion.order.web.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * GET giỏ hiện tại. Chỉ GET được retry tối đa 2 lần (tổng 3 lần gọi) khi 5xx/timeout/IO; 4xx giữ
 * nguyên status + code của cart-coupon-service.
 */
@Component
public class CartClient {

    private static final Logger log = LoggerFactory.getLogger(CartClient.class);
    private static final String SERVICE_NAME = "cart-coupon-service";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 100L;
    private static final Map<Integer, String> FALLBACK_CODES = Map.of(
            404, "CART_NOT_FOUND",
            409, "CART_EMPTY",
            422, "COUPON_INVALID");

    private final RestClient cartRestClient;
    private final InternalRequestHeaders internalHeaders;
    private final UpstreamErrorMapper errorMapper;

    public CartClient(@Qualifier("cartRestClient") RestClient cartRestClient,
                      InternalRequestHeaders internalHeaders,
                      UpstreamErrorMapper errorMapper) {
        this.cartRestClient = cartRestClient;
        this.internalHeaders = internalHeaders;
        this.errorMapper = errorMapper;
    }

    public CartSnapshot fetch(RequestContext context) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CartSnapshot snapshot = cartRestClient.get()
                        .uri("/internal/v1/carts/{userId}", context.userId())
                        .headers(headers -> internalHeaders.apply(headers, context))
                        .retrieve()
                        .body(CartSnapshot.class);
                if (snapshot == null) {
                    throw ApiException.serviceUnavailable(SERVICE_NAME);
                }
                return snapshot;
            } catch (RestClientResponseException failure) {
                if (failure.getStatusCode().is4xxClientError()) {
                    throw errorMapper.passthrough(failure, SERVICE_NAME, FALLBACK_CODES);
                }
                log.warn("Cart call failed status={} attempt={} userId={} traceId={}",
                        failure.getStatusCode().value(), attempt, context.userId(),
                        context.traceId());
            } catch (ResourceAccessException failure) {
                log.warn("Cart call failed attempt={} userId={} traceId={}: {}", attempt,
                        context.userId(), context.traceId(), failure.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                backoff(attempt);
            }
        }
        throw ApiException.serviceUnavailable(SERVICE_NAME);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable(SERVICE_NAME);
        }
    }
}
