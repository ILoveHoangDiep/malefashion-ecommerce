package com.malefashion.catalog.controller;

import com.malefashion.catalog.dto.VariantBatchRequest;
import com.malefashion.catalog.dto.VariantBatchResponse;
import com.malefashion.catalog.service.CatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/variants")
@RequiredArgsConstructor
@Slf4j
public class InternalVariantController {

    private final CatalogService catalogService;

    @PostMapping("/batch")
    public ResponseEntity<VariantBatchResponse> getVariantsBatch(
            @Valid @RequestBody VariantBatchRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService) {

        log.debug("Internal getVariantsBatch request: count={}, traceId={}, caller={}",
                request.getVariantIds().size(), traceId, callerService);

        VariantBatchResponse response = catalogService.getVariantsBatch(request.getVariantIds());
        return ResponseEntity.ok(response);
    }
}
