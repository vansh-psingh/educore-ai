package com.educore.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Single cached lookup for configuration: real environment variables first,
 * then the project .env file (parsed once, lazily). Replaces the per-class
 * duplicated envValue() helpers so config reads do not hit disk per request.
 */
public final class Env {

    private static volatile Map<String, String> fileValues;

    private Env() {
    }

    /** Returns the trimmed value or null when unset/blank. */
    public static String get(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        return fileValues().get(key);
    }

    private static Map<String, String> fileValues() {
        Map<String, String> m = fileValues;
        if (m == null) {
            synchronized (Env.class) {
                m = fileValues;
                if (m == null) {
                    m = new HashMap<>();
                    try {
                        Path p = Path.of(".env");
                        if (Files.isRegularFile(p)) {
                            for (String line : Files.readAllLines(p)) {
                                String t = line.trim();
                                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                                int eq = t.indexOf('=');
                                m.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                    fileValues = m;
                }
            }
        }
        return m;
    }
}
