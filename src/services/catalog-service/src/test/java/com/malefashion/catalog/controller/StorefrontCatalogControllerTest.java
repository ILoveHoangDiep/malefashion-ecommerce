package com.malefashion.catalog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.catalog.api.error.GlobalExceptionHandler;
import com.malefashion.catalog.dto.CategoryDto;
import com.malefashion.catalog.dto.MoneyDto;
import com.malefashion.catalog.dto.ProductDetailDto;
import com.malefashion.catalog.dto.ProductSummaryDto;
import com.malefashion.catalog.service.CatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StorefrontCatalogController.class)
@Import(GlobalExceptionHandler.class)
class StorefrontCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CatalogService catalogService;

    @Test
    @DisplayName("GET /api/v1/products returns paginated products")
    void testGetProducts() throws Exception {
        UUID prodId = UUID.randomUUID();
        ProductSummaryDto summary = ProductSummaryDto.builder()
                .productId(prodId)
                .name("Chino Pants")
                .slug("chino-pants")
                .categoryName("Pants")
                .minPrice(new MoneyDto("450000.00", "VND"))
                .maxPrice(new MoneyDto("490000.00", "VND"))
                .primaryImageUrl("http://localhost:9000/images/pants.jpg")
                .variantCount(4)
                .build();

        PageImpl<ProductSummaryDto> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1);
        when(catalogService.getProducts(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Chino Pants"))
                .andExpect(jsonPath("$.content[0].minPrice.amount").value("450000.00"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns product detail when found")
    void testGetProductById_Found() throws Exception {
        UUID prodId = UUID.randomUUID();
        ProductDetailDto detail = ProductDetailDto.builder()
                .productId(prodId)
                .name("Denim Jacket")
                .slug("denim-jacket")
                .description("Classic blue jacket")
                .categoryName("Jackets")
                .variants(Collections.emptyList())
                .build();

        when(catalogService.getProductById(prodId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/products/{id}", prodId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Denim Jacket"))
                .andExpect(jsonPath("$.categoryName").value("Jackets"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns 404 ProblemDetail when not found")
    void testGetProductById_NotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(catalogService.getProductById(unknownId))
                .thenThrow(new NoSuchElementException("Không tìm thấy sản phẩm với id: " + unknownId));

        mockMvc.perform(get("/api/v1/products/{id}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/categories returns list of categories")
    void testGetCategories() throws Exception {
        CategoryDto categoryDto = CategoryDto.builder()
                .id(UUID.randomUUID())
                .name("Accessories")
                .slug("accessories")
                .build();

        when(catalogService.getCategories()).thenReturn(List.of(categoryDto));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Accessories"))
                .andExpect(jsonPath("$[0].slug").value("accessories"));
    }
}
