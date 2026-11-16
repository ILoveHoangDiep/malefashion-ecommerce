package com.malefashion.inventory.api;

import java.time.Instant;
import java.util.List;

/**
 * Contract shape of {@code application/problem+json} for this service. All fields are always present.
 */
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String traceId,
        Instant timestamp,
        List<ProblemFieldError> errors
) {
}
