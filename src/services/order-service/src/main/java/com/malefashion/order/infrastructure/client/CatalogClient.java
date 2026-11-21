package com.malefashion.order.infrastructure.client;

import com.malefashion.order.web.ApiException;
import com.malefashion.order.web.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST batch variant. Batch là POST nên KHÔNG retry (prompt 3): một lần gọi, 4xx giữ nguyên, còn
 * 5xx/timeout trả 503 SERVICE_UNAVAILABLE.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);
    private static final String SERVICE_NAME = "catalog-service";
    private static final Map<Integer, String> FALLBACK_CODES = Map.of(
            400, "VALIDATION_ERROR");

    private final RestClient catalogRestClient;
    private final InternalRequestHeaders internalHeaders;
    private final UpstreamErrorMapper errorMapper;

    public CatalogClient(@Qualifier("catalogRestClient") RestClient catalogRestClient,
                         InternalRequestHeaders internalHeaders,
                         UpstreamErrorMapper errorMapper) {
        this.catalogRestClient = catalogRestClient;
        this.internalHeaders = internalHeaders;
        this.errorMapper = errorMapper;
    }

    public VariantBatch.Response fetch(List<UUID> variantIds, RequestContext context) {
        try {
            VariantBatch.Response response = catalogRestClient.post()
                    .uri("/internal/v1/variants/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> internalHeaders.apply(headers, context))
                    .body(new VariantBatch.Request(variantIds))
                    .retrieve()
                    .body(VariantBatch.Response.class);
            if (response == null) {
                throw ApiException.serviceUnavailable(SERVICE_NAME);
            }
            return response;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().is4xxClientError()) {
                throw errorMapper.passthrough(failure, SERVICE_NAME, FALLBACK_CODES);
            }
            log.warn("Catalog call failed status={} userId={} traceId={}",
                    failure.getStatusCode().value(), context.userId(), context.traceId());
            throw ApiException.serviceUnavailable(SERVICE_NAME);
        } catch (ResourceAccessException failure) {
            log.warn("Catalog call failed userId={} traceId={}: {}", context.userId(),
                    context.traceId(), failure.getMessage());
            throw ApiException.serviceUnavailable(SERVICE_NAME);
        }
    }
}
