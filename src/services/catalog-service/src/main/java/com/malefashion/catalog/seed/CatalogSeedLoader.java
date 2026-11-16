package com.malefashion.catalog.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.catalog.domain.entity.Category;
import com.malefashion.catalog.domain.entity.Product;
import com.malefashion.catalog.domain.entity.Variant;
import com.malefashion.catalog.repository.CategoryRepository;
import com.malefashion.catalog.repository.ProductRepository;
import com.malefashion.catalog.repository.VariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedLoader implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (variantRepository.count() > 0) {
            log.info("Catalog database already contains variants. Skipping seed loader.");
            return;
        }

        try {
            byte[] content = resolveSeedContent();
            if (content == null || content.length == 0) {
                log.warn("Seed file variants.json could not be found. Skipping seed.");
                return;
            }

            JsonNode root = objectMapper.readTree(content);
            JsonNode productsNode = root.get("products");
            if (productsNode == null || !productsNode.isArray()) {
                log.warn("Invalid variants.json format: missing products array.");
                return;
            }

            Category defaultCategory = categoryRepository.findBySlug("mens-apparel")
                    .orElseGet(() -> categoryRepository.save(
                            Category.builder()
                                    .name("Men's Apparel")
                                    .slug("mens-apparel")
                                    .description("Thời trang nam cao cấp")
                                    .build()
                    ));

            for (JsonNode prodNode : productsNode) {
                UUID productId = UUID.fromString(prodNode.get("productId").asText());
                String productName = prodNode.get("productName").asText();
                String slug = generateSlug(productName);

                Product product = productRepository.findById(productId)
                        .orElseGet(() -> productRepository.save(
                                Product.builder()
                                        .id(productId)
                                        .name(productName)
                                        .slug(slug)
                                        .description(productName + " - Chất liệu cao cấp, form dáng chuẩn đẹp.")
                                        .category(defaultCategory)
                                        .active(true)
                                        .build()
                        ));

                JsonNode variantsNode = prodNode.get("variants");
                if (variantsNode != null && variantsNode.isArray()) {
                    for (JsonNode varNode : variantsNode) {
                        UUID variantId = UUID.fromString(varNode.get("variantId").asText());
                        String sku = varNode.get("sku").asText();
                        String size = varNode.get("size").asText();
                        String color = varNode.get("color").asText();
                        String amountStr = varNode.get("price").get("amount").asText();
                        BigDecimal price = new BigDecimal(amountStr);
                        String imageUrl = varNode.has("imageUrl") ? varNode.get("imageUrl").asText() : null;

                        if (variantRepository.findById(variantId).isEmpty() && variantRepository.findBySku(sku).isEmpty()) {
                            Variant variant = Variant.builder()
                                    .id(variantId)
                                    .product(product)
                                    .sku(sku)
                                    .size(size)
                                    .color(color)
                                    .price(price)
                                    .imageUrl(imageUrl)
                                    .active(true)
                                    .build();
                            variantRepository.save(variant);
                        }
                    }
                }
            }

            log.info("Catalog seed data loaded successfully: {} products, {} variants",
                    productRepository.count(), variantRepository.count());

        } catch (Exception e) {
            log.error("Failed to load catalog seed data: {}", e.getMessage(), e);
        }
    }

    private byte[] resolveSeedContent() {
        String[] candidatePaths = {
                "contracts/seed/variants.json",
                "../contracts/seed/variants.json",
                "../../contracts/seed/variants.json",
                "../../../contracts/seed/variants.json",
                "/contracts/seed/variants.json"
        };

        for (String candidate : candidatePaths) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                try {
                    return Files.readAllBytes(f.toPath());
                } catch (Exception ignored) {
                }
            }
        }

        try (InputStream is = getClass().getResourceAsStream("/seed/variants.json")) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String generateSlug(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
