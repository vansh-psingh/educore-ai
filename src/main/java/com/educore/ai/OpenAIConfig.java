package com.educore.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI configuration loaded from environment variables or a .env file.
 * The API key stays on the backend - it is never sent to the browser.
 */
public final class OpenAIConfig {

    private static OpenAIConfig instance;

    public final String apiKey;
    public final String model;
    public final int maxOutputTokens;
    public final Duration timeout;
    public final boolean configured;

    private OpenAIConfig(String apiKey, String model, int maxOutputTokens, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // treat placeholder values like "YOUR_PRIVATE_OPENAI_API_KEY" as not configured
        this.configured = apiKey != null && !apiKey.isBlank() && !apiKey.contains("YOUR_");
    }

    public static synchronized OpenAIConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static OpenAIConfig load() {
        Map<String, String> env = new HashMap<>();
        // .env file in the working directory (project root when run via 'web' arg)
        Path envFile = Path.of(".env");
        if (Files.isRegularFile(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) {
                        continue;
                    }
                    int eq = t.indexOf('=');
                    env.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
                }
            } catch (IOException ignored) {
            }
        }
        // real environment variables take precedence
        String key = firstNonBlank(System.getenv("OPENAI_API_KEY"), env.get("OPENAI_API_KEY"));
        String model = firstNonBlank(System.getenv("OPENAI_MODEL"), env.get("OPENAI_MODEL"), "gpt-4o-mini");
        int maxTokens = parseInt(firstNonBlank(System.getenv("OPENAI_MAX_OUTPUT_TOKENS"),
                env.get("OPENAI_MAX_OUTPUT_TOKENS")), 1200);
        int timeoutSec = parseInt(firstNonBlank(System.getenv("OPENAI_TIMEOUT_SECONDS"),
                env.get("OPENAI_TIMEOUT_SECONDS")), 60);
        return new OpenAIConfig(key, model, maxTokens, timeoutSec);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
