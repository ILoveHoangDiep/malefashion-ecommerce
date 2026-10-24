package com.malefashion.inventory.infrastructure.persistence;

import com.malefashion.inventory.domain.model.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    Optional<StockItem> findBySku(String sku);

    List<StockItem> findAllByVariantIdIn(Collection<UUID> variantIds);
}
