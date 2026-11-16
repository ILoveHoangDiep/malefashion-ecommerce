package com.malefashion.inventory.api;

/**
 * Item of the {@code errors[]} array in a ProblemDetail response.
 */
public record ProblemFieldError(
        String field,
        String message
) {
}
