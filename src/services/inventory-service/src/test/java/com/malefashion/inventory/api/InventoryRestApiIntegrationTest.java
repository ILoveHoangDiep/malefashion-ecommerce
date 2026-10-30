package com.malefashion.inventory.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.inventory.support.IntegrationTestBase;
import com.malefashion.inventory.support.OpenApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public read API from {@code contracts/openapi/inventory-api.yaml}, exercised through the real
 * filter chain on a real PostgreSQL. Responses are validated against the contract file itself.
 */
@AutoConfigureMockMvc
class InventoryRestApiIntegrationTest extends IntegrationTestBase {

    private static final String VARIANTS = "/api/v1/inventory/variants";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ single variant

    @Test
    void returnsAvailableStockAndOnlyThePublicFields() throws Exception {
        UUID variantId = givenStock("MF-TEE-BLACK-S", 7, 3);

        MvcResult result = mockMvc.perform(get(VARIANTS + "/{variantId}", variantId)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.sku").value("MF-TEE-BLACK-S"))
                .andExpect(jsonPath("$.availableQuantity").value(7))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.reservedQuantity").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(header().string("X-Trace-Id", TRACE_ID))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS + "/" + variantId, OpenApiContract.get(),
                result.getResponse());

        JsonNode body = readBody(result);
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .as("contract fields only: reserved stock and the row version stay internal")
                .containsExactlyInAnyOrder("variantId", "sku", "availableQuantity", "inStock", "updatedAt");

        String updatedAt = body.get("updatedAt").asText();
        assertThat(updatedAt)
                .as("contract timestamps use exactly three fractional digits and a trailing Z")
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
        Instant storedUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM stock_items WHERE variant_id = ?", Timestamp.class, variantId).toInstant();
        assertThat(Instant.parse(updatedAt)).isEqualTo(storedUpdatedAt.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void reportsOutOfStockWhenAvailableQuantityIsZero() throws Exception {
        UUID variantId = givenStock("MF-TEE-WHITE-M", 0, 0);

        MvcResult result = mockMvc.perform(get(VARIANTS + "/{variantId}", variantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.inStock").value(false))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS + "/" + variantId, OpenApiContract.get(),
                result.getResponse());
    }

    @Test
    void returns404ProblemDetailForAVariantWithoutStock() throws Exception {
        UUID unknown = UUID.randomUUID();

        MvcResult result = mockMvc.perform(get(VARIANTS + "/{variantId}", unknown)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://malefashion.local/errors/variant-not-found"))
                .andExpect(jsonPath("$.title").value("Variant not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VARIANT_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(VARIANTS + "/" + unknown))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS + "/" + unknown, OpenApiContract.get(),
                result.getResponse());
    }

    @Test
    void rejectsANonUuidPathWithValidationProblem() throws Exception {
        // The contract declares only 200 and 404 for this path, so this response is checked against
        // the shared ProblemDetail shape by hand rather than through the validator.
        mockMvc.perform(get(VARIANTS + "/not-a-uuid").header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value(VARIANTS + "/not-a-uuid"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].field").value("variantId"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    // ------------------------------------------------------------------ batch

    @Test
    void batchReturnsFoundItemsAndMissingIdsInRequestOrder() throws Exception {
        UUID first = givenStock("MF-TEE-BLACK-S", 4, 0);
        UUID third = givenStock("MF-TEE-WHITE-L", 0, 0);
        UUID missing = UUID.randomUUID();

        String ids = String.join(",", third.toString(), missing.toString(), first.toString());

        MvcResult result = mockMvc.perform(get(VARIANTS).param("ids", ids).header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].variantId").value(third.toString()))
                .andExpect(jsonPath("$.items[0].inStock").value(false))
                .andExpect(jsonPath("$.items[1].variantId").value(first.toString()))
                .andExpect(jsonPath("$.items[1].inStock").value(true))
                .andExpect(jsonPath("$.items[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$.missingIds.length()").value(1))
                .andExpect(jsonPath("$.missingIds[0]").value(missing.toString()))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS, OpenApiContract.get(), result.getResponse());
    }

    @Test
    void batchAcceptsExactlyFiftyIds() throws Exception {
        UUID known = givenStock("MF-TEE-BLACK-M", 2, 0);
        String ids = idsOf(known, 49);

        MvcResult result = mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.missingIds.length()").value(49))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS, OpenApiContract.get(), result.getResponse());
    }

    @Test
    void batchRejectsFiftyOneIds() throws Exception {
        String ids = idsOf(UUID.randomUUID(), 50);

        MvcResult result = mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TOO_MANY_IDS"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Too many ids"))
                .andExpect(jsonPath("$.instance").value(VARIANTS))
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(TRACE_ID_PATTERN)))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS, OpenApiContract.get(), result.getResponse());
    }

    @Test
    void batchCollapsesDuplicateIdsOntoTheirFirstOccurrence() throws Exception {
        UUID known = givenStock("MF-TEE-BLACK-L", 3, 0);
        UUID missing = UUID.randomUUID();
        String ids = String.join(",",
                known.toString(), missing.toString(), known.toString(), missing.toString());

        mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(known.toString()))
                .andExpect(jsonPath("$.missingIds.length()").value(1))
                .andExpect(jsonPath("$.missingIds[0]").value(missing.toString()));
    }

    @Test
    void batchTreatsUuidsCaseInsensitivelyAndAnswersWithLowercase() throws Exception {
        UUID variantId = givenStock("MF-TEE-BLACK-S", 5, 0);

        mockMvc.perform(get(VARIANTS + "/{variantId}", variantId.toString().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variantId.toString()));

        mockMvc.perform(get(VARIANTS).param("ids", variantId.toString().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].variantId").value(variantId.toString()));
    }

    @Test
    void batchTrimsWhitespaceAroundIds() throws Exception {
        UUID variantId = givenStock("MF-TEE-WHITE-S", 9, 0);
        String ids = "  " + variantId + "  ,  " + UUID.randomUUID() + " ";

        mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.missingIds.length()").value(1));
    }

    @Test
    void batchRejectsAMissingIdsParameter() throws Exception {
        MvcResult result = mockMvc.perform(get(VARIANTS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS, OpenApiContract.get(), result.getResponse());
    }

    @Test
    void batchRejectsABlankIdsParameter() throws Exception {
        mockMvc.perform(get(VARIANTS).param("ids", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"));
    }

    @Test
    void batchRejectsAnEmptyElement() throws Exception {
        String ids = UUID.randomUUID() + ", ," + UUID.randomUUID();

        mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"));
    }

    @Test
    void batchRejectsANonUuidId() throws Exception {
        String ids = UUID.randomUUID() + ",not-a-uuid";

        MvcResult result = mockMvc.perform(get(VARIANTS).param("ids", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andReturn();

        OpenApiContract.assertResponseMatches(VARIANTS, OpenApiContract.get(), result.getResponse());
    }

    // ------------------------------------------------------------------ trace ids

    @Test
    void echoesTheGatewayTraceId() throws Exception {
        mockMvc.perform(get(VARIANTS + "/{variantId}", givenStock("MF-TEE-BLACK-S", 1, 0))
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", TRACE_ID));
    }

    @Test
    void generatesATraceIdWhenTheHeaderIsMissing() throws Exception {
        MvcResult result = mockMvc.perform(get(VARIANTS + "/{variantId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern(TRACE_ID_PATTERN)))
                .andReturn();

        String generated = result.getResponse().getHeader("X-Trace-Id");
        assertThat(readBody(result).get("traceId").asText()).isEqualTo(generated);
    }

    @Test
    void replacesAMalformedTraceIdInsteadOfRejectingTheRequest() throws Exception {
        MvcResult result = mockMvc.perform(get(VARIANTS + "/{variantId}", givenStock("MF-TEE-WHITE-S", 1, 0))
                        .header("X-Trace-Id", "not-a-trace-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern(TRACE_ID_PATTERN)))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotEqualTo("not-a-trace-id");
    }

    // ------------------------------------------------------------------ actuator

    @Test
    void actuatorExposesHealthAndProbesOnly() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Writes the row directly so the test controls {@code reserved_quantity} and {@code updated_at};
     * only {@code available_quantity} reaches the API.
     */
    private UUID givenStock(String sku, int available, int reserved) {
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO stock_items
                    (variant_id, sku, available_quantity, reserved_quantity, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, now(), now())
                """, variantId, sku, available, reserved);
        return variantId;
    }

    private static String idsOf(UUID first, int randomCount) {
        List<String> ids = new ArrayList<>(randomCount + 1);
        ids.add(first.toString());
        ids.addAll(IntStream.range(0, randomCount)
                .mapToObj(index -> UUID.randomUUID().toString())
                .collect(Collectors.toList()));
        return String.join(",", ids);
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
