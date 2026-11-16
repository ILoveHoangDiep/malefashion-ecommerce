package com.malefashion.catalog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.catalog.api.error.GlobalExceptionHandler;
import com.malefashion.catalog.dto.*;
import com.malefashion.catalog.service.CatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InternalVariantController.class)
@Import(GlobalExceptionHandler.class)
class InternalVariantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CatalogService catalogService;

    @Test
    @DisplayName("POST /internal/v1/variants/batch returns 200 with variants list")
    void testGetVariantsBatch_Success() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        VariantResponse variantResponse = VariantResponse.builder()
                .variantId(variantId)
                .sku("MF-TEE-BLACK-M")
                .productId(productId)
                .productName("Basic Tee")
                .price(new MoneyDto("299000.00", "VND"))
                .attributes(new VariantAttributesDto("M", "BLACK"))
                .imageUrl("http://localhost:9000/images/tee.jpg")
                .active(true)
                .build();

        VariantBatchResponse response = VariantBatchResponse.builder()
                .variants(List.of(variantResponse))
                .missingIds(Collections.emptyList())
                .build();

        when(catalogService.getVariantsBatch(anyList())).thenReturn(response);

        VariantBatchRequest request = VariantBatchRequest.builder()
                .variantIds(List.of(variantId))
                .build();

        mockMvc.perform(post("/internal/v1/variants/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
                        .header("X-Caller-Service", "order-service")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].sku").value("MF-TEE-BLACK-M"))
                .andExpect(jsonPath("$.variants[0].price.amount").value("299000.00"))
                .andExpect(jsonPath("$.variants[0].price.currency").value("VND"))
                .andExpect(jsonPath("$.missingIds").isEmpty());
    }

    @Test
    @DisplayName("POST /internal/v1/variants/batch returns 400 ProblemDetail when variantIds is empty")
    void testGetVariantsBatch_ValidationError() throws Exception {
        String invalidJson = "{\"variantIds\": []}";

        mockMvc.perform(post("/internal/v1/variants/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("variantIds"));
    }
}
