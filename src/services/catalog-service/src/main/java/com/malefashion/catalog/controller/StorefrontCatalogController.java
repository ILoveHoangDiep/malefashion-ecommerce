package com.malefashion.catalog.controller;

import com.malefashion.catalog.dto.CategoryDto;
import com.malefashion.catalog.dto.ProductDetailDto;
import com.malefashion.catalog.dto.ProductSummaryDto;
import com.malefashion.catalog.service.CatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class StorefrontCatalogController {

    private final CatalogService catalogService;

    @GetMapping("/products")
    public ResponseEntity<Page<ProductSummaryDto>> getProducts(
            @RequestParam(required = false) UUID categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<ProductSummaryDto> products = catalogService.getProducts(categoryId, pageable);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ProductDetailDto> getProductById(@PathVariable UUID id) {
        ProductDetailDto product = catalogService.getProductById(id);
        return ResponseEntity.ok(product);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDto>> getCategories() {
        List<CategoryDto> categories = catalogService.getCategories();
        return ResponseEntity.ok(categories);
    }
}
