package com.malefashion.catalog.service;

import com.malefashion.catalog.domain.entity.Category;
import com.malefashion.catalog.domain.entity.Product;
import com.malefashion.catalog.domain.entity.Variant;
import com.malefashion.catalog.dto.CategoryDto;
import com.malefashion.catalog.dto.ProductDetailDto;
import com.malefashion.catalog.dto.ProductSummaryDto;
import com.malefashion.catalog.dto.VariantBatchResponse;
import com.malefashion.catalog.repository.CategoryRepository;
import com.malefashion.catalog.repository.ProductRepository;
import com.malefashion.catalog.repository.VariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private VariantRepository variantRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CatalogService catalogService;

    private Category testCategory;
    private Product testProduct;
    private Variant variant1;
    private Variant variant2;
    private UUID variantId1;
    private UUID variantId2;
    private UUID productId;

    @BeforeEach
    void setUp() {
        testCategory = Category.builder()
                .id(UUID.randomUUID())
                .name("Men's Apparel")
                .slug("mens-apparel")
                .description("Men fashion")
                .build();

        productId = UUID.randomUUID();
        testProduct = Product.builder()
                .id(productId)
                .name("Oxford Cotton Shirt")
                .slug("oxford-cotton-shirt")
                .description("Premium cotton shirt")
                .category(testCategory)
                .active(true)
                .build();

        variantId1 = UUID.randomUUID();
        variant1 = Variant.builder()
                .id(variantId1)
                .product(testProduct)
                .sku("SHIRT-WHITE-M")
                .size("M")
                .color("WHITE")
                .price(new BigDecimal("350000.00"))
                .imageUrl("http://localhost:9000/catalog/shirt-white-m.jpg")
                .active(true)
                .build();

        variantId2 = UUID.randomUUID();
        variant2 = Variant.builder()
                .id(variantId2)
                .product(testProduct)
                .sku("SHIRT-BLUE-L")
                .size("L")
                .color("BLUE")
                .price(new BigDecimal("390000.00"))
                .imageUrl("http://localhost:9000/catalog/shirt-blue-l.jpg")
                .active(true)
                .build();
    }

    @Test
    @DisplayName("getVariantsBatch returns all variants when IDs exist")
    void testGetVariantsBatch_AllFound() {
        when(variantRepository.findByIdIn(List.of(variantId1, variantId2)))
                .thenReturn(List.of(variant1, variant2));

        VariantBatchResponse response = catalogService.getVariantsBatch(List.of(variantId1, variantId2));

        assertThat(response.getVariants()).hasSize(2);
        assertThat(response.getMissingIds()).isEmpty();
        assertThat(response.getVariants().get(0).getSku()).isEqualTo("SHIRT-WHITE-M");
        assertThat(response.getVariants().get(0).getPrice().getAmount()).isEqualTo("350000.00");
    }

    @Test
    @DisplayName("getVariantsBatch identifies missing variant IDs accurately")
    void testGetVariantsBatch_WithMissingIds() {
        UUID nonExistentId = UUID.randomUUID();
        when(variantRepository.findByIdIn(List.of(variantId1, nonExistentId)))
                .thenReturn(List.of(variant1));

        VariantBatchResponse response = catalogService.getVariantsBatch(List.of(variantId1, nonExistentId));

        assertThat(response.getVariants()).hasSize(1);
        assertThat(response.getMissingIds()).containsExactly(nonExistentId);
    }

    @Test
    @DisplayName("getVariantsBatch handles empty list cleanly")
    void testGetVariantsBatch_EmptyList() {
        VariantBatchResponse response = catalogService.getVariantsBatch(Collections.emptyList());

        assertThat(response.getVariants()).isEmpty();
        assertThat(response.getMissingIds()).isEmpty();
        verifyNoInteractions(variantRepository);
    }

    @Test
    @DisplayName("getProducts by category correctly paginates and computes min/max prices")
    void testGetProducts_WithCategory() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findByCategoryId(testCategory.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(testProduct), pageable, 1));
        when(variantRepository.findByProductId(productId))
                .thenReturn(List.of(variant1, variant2));

        Page<ProductSummaryDto> page = catalogService.getProducts(testCategory.getId(), pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        ProductSummaryDto summary = page.getContent().get(0);
        assertThat(summary.getName()).isEqualTo("Oxford Cotton Shirt");
        assertThat(summary.getMinPrice().getAmount()).isEqualTo("350000.00");
        assertThat(summary.getMaxPrice().getAmount()).isEqualTo("390000.00");
        assertThat(summary.getVariantCount()).isEqualTo(2);
        assertThat(summary.getPrimaryImageUrl()).isEqualTo("http://localhost:9000/catalog/shirt-white-m.jpg");
    }

    @Test
    @DisplayName("getProductById returns product details when product exists")
    void testGetProductById_Found() {
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(variantRepository.findByProductId(productId)).thenReturn(List.of(variant1, variant2));

        ProductDetailDto detail = catalogService.getProductById(productId);

        assertThat(detail.getProductId()).isEqualTo(productId);
        assertThat(detail.getName()).isEqualTo("Oxford Cotton Shirt");
        assertThat(detail.getCategoryName()).isEqualTo("Men's Apparel");
        assertThat(detail.getVariants()).hasSize(2);
    }

    @Test
    @DisplayName("getProductById throws NoSuchElementException when product does not exist")
    void testGetProductById_NotFound() {
        UUID unknownId = UUID.randomUUID();
        when(productRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.getProductById(unknownId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Không tìm thấy sản phẩm");
    }

    @Test
    @DisplayName("getCategories returns list of mapped category DTOs")
    void testGetCategories() {
        when(categoryRepository.findAll()).thenReturn(List.of(testCategory));

        List<CategoryDto> categories = catalogService.getCategories();

        assertThat(categories).hasSize(1);
        assertThat(categories.get(0).getName()).isEqualTo("Men's Apparel");
        assertThat(categories.get(0).getSlug()).isEqualTo("mens-apparel");
    }
}
