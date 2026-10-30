package com.malefashion.inventory.support;

import com.malefashion.inventory.infrastructure.messaging.MessageTypes;
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
 * Validates envelopes against the JSON Schemas that live in the repository under {@code contracts/events}.
 * The files are read from disk on every run; nothing is copied into the test resources.
 */
public final class ContractSchemas {

    private static final String SCHEMA_BASE = "https://malefashion.local/contracts/events/";

    private static final Map<String, String> EVENT_SCHEMA_FILES = Map.of(
            MessageTypes.EVENT_STOCK_RESERVED, "inventory-stock-reserved.json",
            MessageTypes.EVENT_STOCK_RESERVATION_FAILED, "inventory-stock-reservation-failed.json",
            MessageTypes.EVENT_STOCK_COMMITTED, "inventory-stock-committed.json",
            MessageTypes.EVENT_STOCK_COMMIT_FAILED, "inventory-stock-commit-failed.json",
            MessageTypes.EVENT_STOCK_RELEASED, "inventory-stock-released.json"
    );

    private static final Map<String, String> COMMAND_SCHEMA_FILES = Map.of(
            MessageTypes.COMMAND_RESERVE_STOCK, "inventory-reserve-stock.json",
            MessageTypes.COMMAND_COMMIT_STOCK, "inventory-commit-stock.json",
            MessageTypes.COMMAND_RELEASE_STOCK, "inventory-release-stock.json"
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
        validate(schemaFileForEvent(eventType), json);
    }

    public static void validateCommand(String commandType, String json) {
        String file = COMMAND_SCHEMA_FILES.get(commandType);
        if (file == null) {
            throw new IllegalArgumentException("No command schema registered for " + commandType);
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

    private static String schemaFileForEvent(String eventType) {
        String file = EVENT_SCHEMA_FILES.get(eventType);
        if (file == null) {
            throw new IllegalArgumentException("No event schema registered for " + eventType);
        }
        return file;
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
