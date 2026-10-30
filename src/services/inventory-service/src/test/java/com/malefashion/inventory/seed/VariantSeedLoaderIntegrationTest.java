package com.malefashion.inventory.seed;

import com.malefashion.inventory.support.ContractSchemas;
import com.malefashion.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup seed against the real contract file and a real PostgreSQL. Every test starts from the
 * truncated tables of {@link IntegrationTestBase}, so seeding is driven explicitly here.
 */
class VariantSeedLoaderIntegrationTest extends IntegrationTestBase {

    private static final Path SEED_FILE = ContractSchemas.eventsDirectory()
            .getParent()
            .resolve("seed/variants.json");

    /** First variant of the contract file: MF-TEE-BLACK-S with initialStock 32. */
    private static final UUID FIRST_VARIANT = UUID.fromString("514a23b0-f6c0-52a2-95ca-2f5fce134d37");

    @Autowired
    private VariantSeedLoader seedLoader;

    @Test
    void seedsEveryVariantOfTheContractFile() {
        int inserted = seedLoader.seed(SEED_FILE);

        assertThat(inserted).isEqualTo(60);
        assertThat(countStockItems()).isEqualTo(60);
        assertThat(countDistinctSkus()).isEqualTo(60);

        Map<String, Object> first = rowOf(FIRST_VARIANT);
        assertThat(first.get("sku")).isEqualTo("MF-TEE-BLACK-S");
        assertThat(first.get("available_quantity")).isEqualTo(32);
        assertThat(first.get("reserved_quantity")).isEqualTo(0);
        assertThat(first.get("version")).isEqualTo(0L);
    }

    @Test
    void rerunInsertsNothingAndKeepsQuantities() {
        seedLoader.seed(SEED_FILE);
        jdbcTemplate.update("""
                UPDATE stock_items SET available_quantity = 1, reserved_quantity = 2
                WHERE variant_id = ?
                """, FIRST_VARIANT);

        int inserted = seedLoader.seed(SEED_FILE);

        assertThat(inserted).isZero();
        assertThat(countStockItems()).isEqualTo(60);
        Map<String, Object> first = rowOf(FIRST_VARIANT);
        assertThat(first.get("available_quantity")).isEqualTo(1);
        assertThat(first.get("reserved_quantity")).isEqualTo(2);
    }

    @Test
    void insertsOnlyTheVariantsThatAreMissing() {
        seedLoader.seed(SEED_FILE);
        jdbcTemplate.update("DELETE FROM stock_items WHERE variant_id = ?", FIRST_VARIANT);

        int inserted = seedLoader.seed(SEED_FILE);

        assertThat(inserted).isEqualTo(1);
        assertThat(countStockItems()).isEqualTo(60);
        assertThat(rowOf(FIRST_VARIANT).get("available_quantity")).isEqualTo(32);
    }

    @Test
    void insertsAVariantThatIsNotInTheDatabaseYetFromASmallFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("variants.json");
        Files.writeString(file, """
                {
                  "version": 1,
                  "products": [
                    {
                      "productId": "11111111-1111-4111-8111-111111111111",
                      "productName": "Test tee",
                      "variants": [
                        {
                          "variantId": "22222222-2222-4222-8222-222222222222",
                          "sku": "MF-TEST-BLACK-S",
                          "size": "S",
                          "color": "BLACK",
                          "price": 199000.00,
                          "imageUrl": "https://example.test/tee.png",
                          "initialStock": 7
                        }
                      ]
                    }
                  ]
                }
                """);

        assertThat(seedLoader.seed(file)).isEqualTo(1);
        assertThat(seedLoader.seed(file)).isZero();
        Map<String, Object> row = rowOf(UUID.fromString("22222222-2222-4222-8222-222222222222"));
        assertThat(row.get("sku")).isEqualTo("MF-TEST-BLACK-S");
        assertThat(row.get("available_quantity")).isEqualTo(7);
        assertThat(row.get("reserved_quantity")).isEqualTo(0);
    }

    @Test
    void failsClearlyWhenASeedSkuBelongsToAnotherVariant() {
        UUID squatter = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO stock_items
                    (variant_id, sku, available_quantity, reserved_quantity, version, created_at, updated_at)
                VALUES (?, 'MF-TEE-BLACK-S', 5, 0, 0, now(), now())
                """, squatter);

        assertThatThrownBy(() -> seedLoader.seed(SEED_FILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MF-TEE-BLACK-S")
                .hasMessageContaining(squatter.toString())
                .hasMessageContaining(FIRST_VARIANT.toString());

        assertThat(countStockItems())
                .as("a conflicting seed must not leave half the file inserted")
                .isEqualTo(1);
    }

    @Test
    void failsClearlyWhenTheFileIsMissing(@TempDir Path directory) {
        Path missing = directory.resolve("does-not-exist.json");

        assertThatThrownBy(() -> seedLoader.seed(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        assertThat(countStockItems()).isZero();
    }

    @Test
    void failsClearlyWhenTheFileIsNotJson(@TempDir Path directory) throws IOException {
        Path broken = directory.resolve("broken.json");
        Files.writeString(broken, "{ \"products\": [ oops");

        assertThatThrownBy(() -> seedLoader.seed(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");

        assertThat(countStockItems()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private long countStockItems() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_items", Long.class);
        return count == null ? 0 : count;
    }

    private long countDistinctSkus() {
        Long count = jdbcTemplate.queryForObject("SELECT count(DISTINCT sku) FROM stock_items", Long.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> rowOf(UUID variantId) {
        return jdbcTemplate.queryForMap("""
                SELECT sku, available_quantity, reserved_quantity, version
                FROM stock_items WHERE variant_id = ?
                """, variantId);
    }
}
