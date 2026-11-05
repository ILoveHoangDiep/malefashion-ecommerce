package com.malefashion.payment.support;

import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validate envelope/payload against JSON Schema trong {@code contracts/events}.
 *
 * <p>Cùng cách làm với inventory-service: đọc schema trực tiếp từ repo mỗi lần chạy, không copy vào
 * test resources, để test đỏ ngay khi contract thay đổi.</p>
 */
public final class ContractSchemas {

    private static final String SCHEMA_BASE = "https://malefashion.local/contracts/events/";

    private static final Map<String, String> EVENT_SCHEMA_FILES = Map.of(
            MessageTypes.EVENT_PAYMENT_SUCCEEDED, "payment-succeeded.json",
            MessageTypes.EVENT_PAYMENT_FAILED, "payment-failed.json",
            MessageTypes.EVENT_PAYMENT_REFUNDED, "payment-refunded.json"
    );

    private static final Path EVENTS_DIR = locateEventsDirectory();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.resourceLoaders(loaders -> loaders.resources(ContractSchemas::readSchema)));

    private ContractSchemas() {
    }

    public static Path eventsDirectory() {
        return EVENTS_DIR;
    }

    public static void validateEvent(String eventType, String json) {
        String file = EVENT_SCHEMA_FILES.get(eventType);
        if (file == null) {
            throw new IllegalArgumentException("No event schema registered for " + eventType);
        }
        validate(file, json);
    }

    public static void validate(String schemaFile, String json) {
        Schema schema = REGISTRY.getSchema(SchemaLocation.of(SCHEMA_BASE + schemaFile));
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String details = errors.stream()
                    .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                    .collect(Collectors.joining("\n  "));
            throw new AssertionError("JSON does not match " + schemaFile + ":\n  " + details
                    + "\nDocument:\n" + json);
        }
    }

    private static String readSchema(String location) {
        String fileName = location.substring(location.lastIndexOf('/') + 1);
        Path schemaFile = EVENTS_DIR.resolve(fileName);
        if (!Files.isRegularFile(schemaFile)) {
            throw new IllegalArgumentException("Unknown schema resource requested: " + location);
        }
        try {
            return Files.readString(schemaFile);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + schemaFile, failure);
        }
    }

    private static Path locateEventsDirectory() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("events");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "contracts/events directory not found above " + Paths.get("").toAbsolutePath());
    }
}
