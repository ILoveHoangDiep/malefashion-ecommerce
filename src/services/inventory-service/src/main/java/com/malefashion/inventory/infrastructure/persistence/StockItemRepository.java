package com.malefashion.inventory.infrastructure.persistence;

import com.malefashion.inventory.domain.model.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    Optional<StockItem> findBySku(String sku);

    List<StockItem> findAllByVariantIdIn(Collection<UUID> variantIds);

    /**
     * Locks every requested row in ascending variantId order to keep concurrent reservations deadlock-free.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.variantId in :variantIds order by s.variantId")
    List<StockItem> findAllByVariantIdInForUpdate(@Param("variantIds") Collection<UUID> variantIds);
}
