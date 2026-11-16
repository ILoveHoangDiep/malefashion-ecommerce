package com.malefashion.catalog.service;

import com.malefashion.catalog.domain.entity.Category;
import com.malefashion.catalog.domain.entity.Product;
import com.malefashion.catalog.domain.entity.Variant;
import com.malefashion.catalog.dto.*;
import com.malefashion.catalog.repository.CategoryRepository;
import com.malefashion.catalog.repository.ProductRepository;
import com.malefashion.catalog.repository.VariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CatalogService {

    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;
    private final CategoryRepository categoryRepository;

    public VariantBatchResponse getVariantsBatch(List<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return VariantBatchResponse.builder().build();
        }

        List<Variant> foundVariants = variantRepository.findByIdIn(variantIds);
        Map<UUID, Variant> variantMap = foundVariants.stream()
                .collect(Collectors.toMap(Variant::getId, v -> v, (existing, replacing) -> existing));

        List<VariantResponse> responses = foundVariants.stream()
                .map(this::mapToVariantResponse)
                .toList();

        List<UUID> missingIds = variantIds.stream()
                .filter(id -> !variantMap.containsKey(id))
                .distinct()
                .toList();

        return VariantBatchResponse.builder()
                .variants(responses)
                .missingIds(missingIds)
                .build();
    }

    @Cacheable(
            value = "products",
            key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + (#categoryId != null ? #categoryId.toString() : 'all')"
    )
    public Page<ProductSummaryDto> getProducts(UUID categoryId, Pageable pageable) {
        Page<Product> page = (categoryId != null)
                ? productRepository.findByCategoryId(categoryId, pageable)
                : productRepository.findAll(pageable);

        List<ProductSummaryDto> dtos = page.getContent().stream()
                .map(this::mapToProductSummaryDto)
                .toList();

        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    @Cacheable(value = "product", key = "#id.toString()")
    public ProductDetailDto getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy sản phẩm với id: " + id));

        return mapToProductDetailDto(product);
    }

    @Cacheable(value = "categories", key = "'all'")
    public List<CategoryDto> getCategories() {
        return categoryRepository.findAll().stream()
                .map(cat -> CategoryDto.builder()
                        .id(cat.getId())
                        .name(cat.getName())
                        .slug(cat.getSlug())
                        .description(cat.getDescription())
                        .parentId(cat.getParent() != null ? cat.getParent().getId() : null)
                        .build())
                .toList();
    }

    public VariantResponse mapToVariantResponse(Variant variant) {
        return VariantResponse.builder()
                .variantId(variant.getId())
                .sku(variant.getSku())
                .productId(variant.getProduct() != null ? variant.getProduct().getId() : null)
                .productName(variant.getProduct() != null ? variant.getProduct().getName() : null)
                .price(MoneyDto.of(variant.getPrice()))
                .attributes(VariantAttributesDto.builder()
                        .size(variant.getSize())
                        .color(variant.getColor())
                        .build())
                .imageUrl(variant.getImageUrl())
                .active(Boolean.TRUE.equals(variant.getActive()))
                .build();
    }

    private ProductSummaryDto mapToProductSummaryDto(Product product) {
        List<Variant> variants = variantRepository.findByProductId(product.getId());

        BigDecimal min = variants.stream()
                .map(Variant::getPrice)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal max = variants.stream()
                .map(Variant::getPrice)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        String primaryImageUrl = variants.stream()
                .map(Variant::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);

        return ProductSummaryDto.builder()
                .productId(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .minPrice(MoneyDto.of(min))
                .maxPrice(MoneyDto.of(max))
                .primaryImageUrl(primaryImageUrl)
                .variantCount(variants.size())
                .build();
    }

    private ProductDetailDto mapToProductDetailDto(Product product) {
        List<Variant> variants = variantRepository.findByProductId(product.getId());
        List<VariantResponse> variantResponses = variants.stream()
                .map(this::mapToVariantResponse)
                .toList();

        return ProductDetailDto.builder()
                .productId(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .description(product.getDescription())
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .variants(variantResponses)
                .build();
    }
}
