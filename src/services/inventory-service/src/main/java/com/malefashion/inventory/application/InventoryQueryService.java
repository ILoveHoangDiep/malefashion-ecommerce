package com.malefashion.inventory.application;

import com.malefashion.inventory.api.InventoryApiException;
import com.malefashion.inventory.api.StockBatchResponse;
import com.malefashion.inventory.api.StockItemResponse;
import com.malefashion.inventory.api.StockSummaryResponse;
import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.persistence.StockItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only stock lookups for the public API.
 *
 * <p>Deliberately plain reads: no pessimistic lock, no retry, no event. Background reservations are
 * the only writers and a reader that momentarily sees the pre-commit value is acceptable for the
 * storefront.</p>
 */
@Service
public class InventoryQueryService {

    private final StockItemRepository stockItemRepository;

    public InventoryQueryService(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    @Transactional(readOnly = true)
    public StockItemResponse findVariant(UUID variantId) {
        return stockItemRepository.findById(variantId)
                .map(InventoryQueryService::toStockItem)
                .orElseThrow(InventoryApiException::variantNotFound);
    }

    /**
     * @param variantIds unique ids in caller order; items come back in that same order and ids without
     *                   a stock row are reported in {@code missingIds}, also in caller order.
     */
    @Transactional(readOnly = true)
    public StockBatchResponse findVariants(List<UUID> variantIds) {
        List<StockItem> found = stockItemRepository.findAllById(variantIds);
        Map<UUID, StockItem> byVariantId = new LinkedHashMap<>();
        for (StockItem item : found) {
            byVariantId.put(item.getVariantId(), item);
        }

        List<StockSummaryResponse> items = new ArrayList<>(variantIds.size());
        List<UUID> missingIds = new ArrayList<>();
        for (UUID variantId : variantIds) {
            StockItem item = byVariantId.get(variantId);
            if (item == null) {
                missingIds.add(variantId);
            } else {
                items.add(new StockSummaryResponse(
                        item.getVariantId(),
                        item.getSku(),
                        item.getAvailableQuantity(),
                        item.getAvailableQuantity() > 0));
            }
        }
        return new StockBatchResponse(items, missingIds);
    }

    private static StockItemResponse toStockItem(StockItem item) {
        return new StockItemResponse(
                item.getVariantId(),
                item.getSku(),
                item.getAvailableQuantity(),
                item.getAvailableQuantity() > 0,
                item.getUpdatedAt());
    }
}
