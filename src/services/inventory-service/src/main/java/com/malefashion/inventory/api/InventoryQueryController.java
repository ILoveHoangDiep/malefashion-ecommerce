package com.malefashion.inventory.api;

import com.malefashion.inventory.application.InventoryQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public read API from {@code contracts/openapi/inventory-api.yaml}.
 *
 * <p>Authentication is the gateway's job; this service only trusts the headers it is given and never
 * verifies JWTs itself.</p>
 */
@RestController
@RequestMapping(path = "/api/v1/inventory/variants", produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryQueryController {

    static final int MAX_IDS = 50;

    /** {@link UUID#fromString} is lenient about short forms, the contract's {@code format: uuid} is not. */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final InventoryQueryService inventoryQueryService;

    public InventoryQueryController(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping("/{variantId}")
    public StockItemResponse getVariantStock(@PathVariable String variantId) {
        return inventoryQueryService.findVariant(parseUuid(variantId, "variantId"));
    }

    @GetMapping
    public StockBatchResponse getVariantsStock(@RequestParam(name = "ids", required = false) String ids) {
        return inventoryQueryService.findVariants(parseIds(ids));
    }

    /**
     * Comma separated, whitespace tolerated, UUIDs case-insensitive, duplicates collapsed onto their
     * first occurrence so the response order follows the caller's order.
     */
    private static List<UUID> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw InventoryApiException.validation("ids", "Phải cung cấp ít nhất một ID.");
        }

        String[] tokens = ids.split(",", -1);
        if (tokens.length > MAX_IDS) {
            throw InventoryApiException.tooManyIds();
        }

        Set<UUID> unique = new LinkedHashSet<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw InventoryApiException.validation("ids", "Danh sách ID chứa phần tử rỗng.");
            }
            unique.add(parseUuid(trimmed, "ids"));
        }
        return new ArrayList<>(unique);
    }

    private static UUID parseUuid(String value, String field) {
        String trimmed = value.trim();
        if (!CANONICAL_UUID.matcher(trimmed).matches()) {
            throw InventoryApiException.validation(field, "Giá trị không phải UUID hợp lệ: " + trimmed);
        }
        return UUID.fromString(trimmed.toLowerCase(Locale.ROOT));
    }
}
