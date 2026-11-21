package com.malefashion.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * Request/response của catalog-api.yaml; catalog là source of truth cho SKU, thuộc tính và giá.
 */
public final class VariantBatch {

    private VariantBatch() {
    }

    public record Request(List<UUID> variantIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(List<VariantItem> variants, List<UUID> missingIds) {

        public Response {
            variants = variants == null ? List.of() : List.copyOf(variants);
            missingIds = missingIds == null ? List.of() : List.copyOf(missingIds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariantItem(
            UUID variantId,
            String sku,
            UUID productId,
            String productName,
            UpstreamMoney price,
            VariantAttributes attributes,
            String imageUrl,
            Boolean active
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariantAttributes(String size, String color) {
    }
}
