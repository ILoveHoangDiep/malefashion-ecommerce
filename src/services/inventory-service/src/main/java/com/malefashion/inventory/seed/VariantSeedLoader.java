package com.malefashion.inventory.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.inventory.config.SeedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads {@code contracts/seed/variants.json} into {@code stock_items} on startup.
 *
 * <p>Seed rows only fill gaps: a variant that already has a stock row keeps its quantities untouched,
 * so restarting the service (or the whole stack) never resets stock that orders have moved. A SKU
 * that now belongs to a different variant is a data error, not something to paper over, so it stops
 * the startup.</p>
 */
@Component
public class VariantSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VariantSeedLoader.class);

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO stock_items
                (variant_id, sku, available_quantity, reserved_quantity, version, created_at, updated_at)
            VALUES (?, ?, ?, 0, 0, ?, ?)
            ON CONFLICT (variant_id) DO NOTHING
            """;

    private final SeedProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public VariantSeedLoader(SeedProperties properties,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper,
                             TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.debug("inventory.seed.enabled=false, skipping variant seed");
            return;
        }
        String configured = properties.path();
        if (configured == null || configured.isBlank()) {
            log.error("Variant seed failed: inventory.seed.path is not set");
            throw new IllegalStateException("Variant seed failed: inventory.seed.path is not set");
        }
        Path seedFile = resolveSeedPath(Path.of("").toAbsolutePath(), configured);
        int inserted = seed(seedFile);
        log.info("Variant seed finished from {}: {} inserted", seedFile.toAbsolutePath(), inserted);
    }

    /**
     * Inserts every variant of the file that has no stock row yet and returns how many rows it added.
     *
     * @throws IllegalStateException when the file is missing, malformed, or conflicts with existing
     *                               stock. Startup must not continue with half-seeded data.
     */
    public int seed(Path seedFile) {
        List<VariantToInsert> variants = readVariants(seedFile);
        Integer inserted = transactionTemplate.execute(status -> insertMissing(seedFile, variants));
        return inserted == null ? 0 : inserted;
    }

    private int insertMissing(Path seedFile, List<VariantToInsert> variants) {
        Map<String, UUID> ownerBySku = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT sku, variant_id FROM stock_items")) {
            ownerBySku.put((String) row.get("sku"), (UUID) row.get("variant_id"));
        }

        Instant now = Instant.now();
        int inserted = 0;
        for (VariantToInsert variant : variants) {
            UUID skuOwner = ownerBySku.get(variant.sku());
            if (skuOwner != null && !skuOwner.equals(variant.variantId())) {
                throw fail(seedFile, "SKU " + variant.sku() + " already belongs to variant " + skuOwner
                        + ", refusing to insert it for variant " + variant.variantId());
            }
            try {
                int rows = jdbcTemplate.update(INSERT_IF_ABSENT,
                        variant.variantId(),
                        variant.sku(),
                        variant.initialStock(),
                        Timestamp.from(now),
                        Timestamp.from(now));
                if (rows > 0) {
                    inserted++;
                    ownerBySku.put(variant.sku(), variant.variantId());
                }
            } catch (DataIntegrityViolationException violation) {
                throw fail(seedFile, "database rejected variant " + variant.variantId()
                        + " (sku " + variant.sku() + "): " + violation.getMostSpecificCause().getMessage(),
                        violation);
            }
        }
        return inserted;
    }

    private List<VariantToInsert> readVariants(Path seedFile) {
        if (!Files.isRegularFile(seedFile)) {
            throw fail(seedFile, "seed file does not exist");
        }
        SeedFile file;
        try (InputStream input = Files.newInputStream(seedFile)) {
            file = objectMapper.readValue(input, SeedFile.class);
        } catch (IOException failure) {
            throw fail(seedFile, "seed file is not valid JSON: " + failure.getMessage(), failure);
        }
        if (file == null || file.products() == null) {
            throw fail(seedFile, "seed file has no products array");
        }

        List<VariantToInsert> variants = new ArrayList<>();
        for (SeedProduct product : file.products()) {
            if (product == null || product.variants() == null) {
                continue;
            }
            for (SeedVariant variant : product.variants()) {
                variants.add(validate(seedFile, variant));
            }
        }
        if (variants.isEmpty()) {
            throw fail(seedFile, "seed file contains no variants");
        }
        return variants;
    }

    private VariantToInsert validate(Path seedFile, SeedVariant variant) {
        if (variant == null || variant.variantId() == null || variant.variantId().isBlank()) {
            throw fail(seedFile, "a variant has no variantId");
        }
        if (variant.sku() == null || variant.sku().isBlank()) {
            throw fail(seedFile, "variant " + variant.variantId() + " has no sku");
        }
        if (variant.initialStock() == null || variant.initialStock() < 0) {
            throw fail(seedFile, "variant " + variant.variantId() + " has an invalid initialStock");
        }
        UUID variantId;
        try {
            variantId = UUID.fromString(variant.variantId());
        } catch (IllegalArgumentException invalid) {
            throw fail(seedFile, "variant has an invalid variantId: " + variant.variantId(), invalid);
        }
        return new VariantToInsert(variantId, variant.sku(), variant.initialStock());
    }

    private static IllegalStateException fail(Path seedFile, String message) {
        log.error("Variant seed failed ({}): {}", seedFile.toAbsolutePath(), message);
        return new IllegalStateException("Variant seed failed: " + message + " [" + seedFile.toAbsolutePath() + "]");
    }

    private static IllegalStateException fail(Path seedFile, String message, Throwable cause) {
        log.error("Variant seed failed ({}): {}", seedFile.toAbsolutePath(), message, cause);
        return new IllegalStateException("Variant seed failed: " + message + " [" + seedFile.toAbsolutePath() + "]",
                cause);
    }

    /**
     * Relative paths are resolved against the working directory, then against its ancestors, so both
     * {@code ./mvnw spring-boot:run} from this service directory and a jar started from the
     * repository root find the same contract file.
     */
    static Path resolveSeedPath(Path workingDirectory, String configured) {
        Path configuredPath = Path.of(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        Path direct = workingDirectory.resolve(configuredPath).normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        for (Path directory = workingDirectory; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(configuredPath).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return direct;
    }

    record SeedFile(List<SeedProduct> products) {
    }

    record SeedProduct(List<SeedVariant> variants) {
    }

    /**
     * Only the fields the loader needs; the contract file carries more (price, images) that belong to
     * the catalog, not here.
     */
    record SeedVariant(String variantId, String sku, Integer initialStock) {
    }

    private record VariantToInsert(UUID variantId, String sku, int initialStock) {
    }
}
