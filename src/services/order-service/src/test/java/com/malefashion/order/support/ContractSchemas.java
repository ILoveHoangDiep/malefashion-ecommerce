package com.malefashion.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
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
 * Validate envelope/payload với JSON Schema sống trong {@code contracts/events}. Không copy schema
 * vào test resources để test đỏ ngay khi contract đổi.
 */
public final class ContractSchemas {

    private static final String SCHEMA_BASE = "https://malefashion.local/contracts/events/";

    private static final Map<String, String> SCHEMA_FILES = Map.ofEntries(
            Map.entry(MessageTypes.EVENT_ORDER_CREATED, "order-created.json"),
            Map.entry(MessageTypes.EVENT_ORDER_CONFIRMED, "order-confirmed.json"),
            Map.entry(MessageTypes.EVENT_ORDER_CANCELLED, "order-cancelled.json"),
            Map.entry(MessageTypes.COMMAND_RESERVE_STOCK, "inventory-reserve-stock.json"),
            Map.entry(MessageTypes.COMMAND_COMMIT_STOCK, "inventory-commit-stock.json"),
            Map.entry(MessageTypes.COMMAND_RELEASE_STOCK, "inventory-release-stock.json"),
            Map.entry(MessageTypes.COMMAND_PROCESS_PAYMENT, "payment-process-payment.json"),
            Map.entry(MessageTypes.COMMAND_REFUND_PAYMENT, "payment-refund-payment.json"),
            Map.entry(MessageTypes.EVENT_STOCK_RESERVED, "inventory-stock-reserved.json"),
            Map.entry(MessageTypes.EVENT_STOCK_RESERVATION_FAILED,
                    "inventory-stock-reservation-failed.json"),
            Map.entry(MessageTypes.EVENT_STOCK_COMMITTED, "inventory-stock-committed.json"),
            Map.entry(MessageTypes.EVENT_STOCK_COMMIT_FAILED, "inventory-stock-commit-failed.json"),
            Map.entry(MessageTypes.EVENT_STOCK_RELEASED, "inventory-stock-released.json"),
            Map.entry(MessageTypes.EVENT_PAYMENT_SUCCEEDED, "payment-succeeded.json"),
            Map.entry(MessageTypes.EVENT_PAYMENT_FAILED, "payment-failed.json"),
            Map.entry(MessageTypes.EVENT_PAYMENT_REFUNDED, "payment-refunded.json"));

    private static final Path EVENTS_DIR = locateEventsDirectory();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.resourceLoaders(
                    loaders -> loaders.resources(ContractSchemas::readSchema)));

    private ContractSchemas() {
    }

    public static Path eventsDirectory() {
        return EVENTS_DIR;
    }

    public static void validate(String eventType, String json) {
        String file = SCHEMA_FILES.get(eventType);
        if (file == null) {
            throw new IllegalArgumentException("No schema registered for " + eventType);
        }
        validateFile(file, json);
    }

    public static void validateFile(String schemaFile, String json) {
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

    public static JsonNode firstExample(ObjectMapper objectMapper, String schemaFile)
            throws IOException {
        Path file = EVENTS_DIR.resolve(schemaFile);
        JsonNode schema = objectMapper.readTree(Files.readString(file));
        JsonNode examples = schema.path("examples");
        if (!examples.isArray() || examples.isEmpty()) {
            throw new AssertionError("examples[] missing in " + schemaFile);
        }
        return examples.get(0);
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
