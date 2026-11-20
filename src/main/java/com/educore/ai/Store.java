package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON-file persistence under ./data/store/ (relative to working dir).
 * Suitable for the single-user dev deployment; replace with a real DB
 * (Postgres) when multi-user auth is added.
 */
public final class Store {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DIR = Path.of("data", "store");

    private Store() {
    }

    public static Path dir() {
        return DIR;
    }

    /** Reads a JSON store file; returns an empty object node if missing. */
    public static synchronized JsonNode read(String name) {
        Path p = DIR.resolve(name);
        try {
            if (Files.isRegularFile(p)) {
                return MAPPER.readTree(p.toFile());
            }
        } catch (IOException ignored) {
        }
        return MAPPER.createObjectNode();
    }

    /** Writes a JSON store file, creating directories as needed. */
    public static synchronized void write(String name, JsonNode node) {
        try {
            Files.createDirectories(DIR);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve(name).toFile(), node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write store file: " + name, e);
        }
    }

    public static ObjectNode obj(JsonNode node) {
        return node instanceof ObjectNode o ? o : MAPPER.createObjectNode();
    }
}
