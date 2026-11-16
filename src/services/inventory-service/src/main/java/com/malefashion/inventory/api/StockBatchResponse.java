package com.malefashion.inventory.api;

import java.util.List;
import java.util.UUID;

public record StockBatchResponse(
        List<StockSummaryResponse> items,
        List<UUID> missingIds
) {
}
