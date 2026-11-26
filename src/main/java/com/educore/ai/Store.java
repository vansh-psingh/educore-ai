package com.educore.ai;

import com.educore.ai.db.Db;
import com.educore.ai.db.EntityStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistence facade. When DATABASE_URL (PostgreSQL) is configured, all store
 * files are served by relational tables via {@link EntityStore}; otherwise the
 * legacy JSON files under ./data/store/ are used (development fallback).
 *
 * The public API is unchanged so every service keeps working.
 */
public final class Store {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DIR = Path.of("data", "store");

    private Store() {
    }

    public static Path dir() {
        return DIR;
    }

    public static boolean dbMode() {
        return Db.configured();
    }

    /** Reads a store document; DB-backed when configured, else JSON file. */
    public static synchronized JsonNode read(String name) {
        EntityStore.Spec spec = EntityStore.SPECS.get(name);
        if (Db.configured() && spec != null) {
            try {
                return EntityStore.read(spec);
            } catch (Exception e) {
                throw new IllegalStateException("DB read failed for " + name + ": " + e.getMessage(), e);
            }
        }
        return readJson(name);
    }

    /** Writes a store document; diffs+upserts in DB mode, else JSON file. */
    public static synchronized void write(String name, JsonNode node) {
        EntityStore.Spec spec = EntityStore.SPECS.get(name);
        if (Db.configured() && spec != null) {
            try {
                EntityStore.write(spec, node);
                return;
            } catch (Exception e) {
                throw new IllegalStateException("DB write failed for " + name + ": " + e.getMessage(), e);
            }
        }
        writeJson(name, node);
    }

    /** Raw JSON-file read, bypassing the DB (used by the migration tool). */
    public static synchronized JsonNode readJson(String name) {
        Path p = DIR.resolve(name);
        try {
            if (Files.isRegularFile(p)) {
                return MAPPER.readTree(p.toFile());
            }
        } catch (IOException ignored) {
        }
        return MAPPER.createObjectNode();
    }

    public static ObjectNode obj(JsonNode node) {
        return node instanceof ObjectNode o ? o : MAPPER.createObjectNode();
    }

    private static void writeJson(String name, JsonNode node) {
        try {
            Files.createDirectories(DIR);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve(name).toFile(), node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write store file: " + name, e);
        }
    }
}
