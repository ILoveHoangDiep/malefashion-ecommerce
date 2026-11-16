package com.malefashion.inventory.application;

import com.malefashion.inventory.domain.model.StockItem;
import com.malefashion.inventory.infrastructure.persistence.StockItemRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Locks stock rows in ascending variantId order (the repository query sorts) so that two multi-variant
 * commands touching the same rows cannot deadlock each other.
 */
@Component
public class StockItemLocker {

    private final StockItemRepository stockItemRepository;

    public StockItemLocker(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    public Map<UUID, StockItem> lockByVariantId(Collection<UUID> variantIds) {
        Map<UUID, StockItem> byVariantId = new LinkedHashMap<>();
        if (variantIds.isEmpty()) {
            return byVariantId;
        }
        for (StockItem stockItem : stockItemRepository.findAllByVariantIdInForUpdate(variantIds)) {
            byVariantId.put(stockItem.getVariantId(), stockItem);
        }
        return byVariantId;
    }
}
