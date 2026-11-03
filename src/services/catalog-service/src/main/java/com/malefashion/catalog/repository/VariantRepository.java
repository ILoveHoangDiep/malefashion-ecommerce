package com.malefashion.catalog.repository;

import com.malefashion.catalog.domain.entity.Variant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VariantRepository extends JpaRepository<Variant, UUID> {

    List<Variant> findByIdIn(List<UUID> ids);

    List<Variant> findByProductId(UUID productId);

    Optional<Variant> findBySku(String sku);
}
