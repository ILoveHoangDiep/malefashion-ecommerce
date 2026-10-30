package com.malefashion.inventory.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Checks real HTTP responses against {@code contracts/openapi/inventory-api.yaml}.
 *
 * <p>Only responses are validated: the contract marks {@code X-Trace-Id} as a required request header,
 * while this service must still answer callers that omit it.</p>
 */
public final class OpenApiContract {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(specificationPath().toUri().toString())
            .build();

    private OpenApiContract() {
    }

    public static Path specificationPath() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("openapi").resolve("inventory-api.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "contracts/openapi/inventory-api.yaml not found above " + Paths.get("").toAbsolutePath());
    }

    /**
     * @param path the concrete request path; the validator maps it onto the spec's path template
     */
    public static void assertResponseMatches(String path, Request.Method method, MockHttpServletResponse response) {
        SimpleResponse.Builder builder = new SimpleResponse.Builder(response.getStatus());
        String contentType = response.getContentType();
        if (contentType != null) {
            builder.withContentType(contentType);
        }
        String body = bodyOf(response);
        if (!body.isEmpty()) {
            builder.withBody(body);
        }

        ValidationReport report = VALIDATOR.validateResponse(path, method, builder.build());
        List<ValidationReport.Message> errors = report.getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            StringBuilder details = new StringBuilder();
            for (ValidationReport.Message error : errors) {
                details.append("\n  - ").append(error.getMessage());
            }
            throw new AssertionError("Response for " + method + " " + path
                    + " does not match the OpenAPI contract:" + details + "\nBody:\n" + body);
        }
    }

    public static Request.Method get() {
        return Request.Method.GET;
    }

    private static String bodyOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
