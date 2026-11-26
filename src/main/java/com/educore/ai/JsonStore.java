package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

/** Loads JSON resources from the classpath into Jackson trees. */
public final class JsonStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Classpath resources are immutable at runtime - parse each once. */
    private static final java.util.Map<String, JsonNode> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private JsonStore() {
    }

    public static JsonNode load(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, JsonStore::readResource);
    }

    private static JsonNode readResource(String resourcePath) {
        try (InputStream in = JsonStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load JSON resource: " + resourcePath, e);
        }
    }
}
