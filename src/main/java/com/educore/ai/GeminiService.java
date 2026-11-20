package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Google Gemini client (generativelanguage API).
 * Same generate() contract as OpenAIService; key stays server-side.
 */
public final class GeminiService {

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String generate(String instructions, String input) throws OpenAIService.OpenAIException {
        String key = firstNonBlank(System.getenv("GEMINI_API_KEY"), envValue("GEMINI_API_KEY"));
        String model = firstNonBlank(System.getenv("GEMINI_MODEL"), envValue("GEMINI_MODEL"), "gemini-2.5-flash");
        int maxTokens = OpenAIConfig.get().maxOutputTokens;
        Duration timeout = OpenAIConfig.get().timeout;

        if (key == null || key.isBlank() || key.contains("YOUR_")) {
            throw new OpenAIService.OpenAIException(503, "AI service is not configured (missing GEMINI_API_KEY).");
        }

        ObjectNode body = mapper.createObjectNode();
        body.putObject("system_instruction").putArray("parts").addObject().put("text", instructions);
        ObjectNode content = body.putArray("contents").addObject();
        content.putArray("parts").addObject().put("text", input);
        body.putObject("generationConfig").put("maxOutputTokens", maxTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + model + ":generateContent?key=" + key))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OpenAIService.OpenAIException(504, "The AI service took too long to respond.");
        } catch (java.io.IOException e) {
            throw new OpenAIService.OpenAIException(503, "The AI service could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAIService.OpenAIException(503, "The request was interrupted.");
        }

        int code = response.statusCode();
        if (code == 400 || code == 401 || code == 403) {
            throw new OpenAIService.OpenAIException(502, "The AI service credentials need administrator attention.");
        }
        if (code == 429) {
            throw new OpenAIService.OpenAIException(429, "The AI service is temporarily rate limited. Please try again later.");
        }
        if (code == 404) {
            throw new OpenAIService.OpenAIException(502, "The configured AI model was not found for this API project.");
        }
        if (code < 200 || code >= 300) {
            throw new OpenAIService.OpenAIException(502, "The AI service could not complete this request (HTTP " + code + ").");
        }

        try {
            return extractText(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new OpenAIService.OpenAIException(502, "Could not parse the AI service response.");
        }
    }

    /** Generate from arbitrary Gemini "contents" (multi-turn history, images). */
    public String generateWithContents(String instructions, JsonNode contents) throws OpenAIService.OpenAIException {
        ObjectNode body = requestBody(contents, instructions);
        HttpResponse<String> response = send(body);
        int code = response.statusCode();
        checkStatus(code, response.body());
        try {
            return extractText(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new OpenAIService.OpenAIException(502, "Could not parse the AI service response.");
        }
    }

    /** Stream generateContent (SSE); onDelta receives text fragments. */
    public void streamWithContents(String instructions, JsonNode contents,
                                   java.util.function.Consumer<String> onDelta)
            throws OpenAIService.OpenAIException {
        ObjectNode body = requestBody(contents, instructions);
        String key = geminiKey();
        String model = geminiModel();
        Duration timeout = OpenAIConfig.get().timeout;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + model + ":streamGenerateContent?alt=sse&key=" + key))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<java.io.InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OpenAIService.OpenAIException(504, "The AI service took too long to respond.");
        } catch (java.io.IOException e) {
            throw new OpenAIService.OpenAIException(503, "The AI service could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAIService.OpenAIException(503, "The request was interrupted.");
        }

        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            String errBody;
            try {
                errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                errBody = "";
            }
            checkStatus(code, errBody);
            throw new OpenAIService.OpenAIException(502, "The AI service could not complete this request (HTTP " + code + ").");
        }

        StringBuilder full = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                try {
                    JsonNode chunk = mapper.readTree(data);
                    for (JsonNode cand : chunk.path("candidates")) {
                        for (JsonNode part : cand.path("content").path("parts")) {
                            String t = part.path("text").asText("");
                            if (!t.isEmpty()) {
                                full.append(t);
                                onDelta.accept(t);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // skip malformed SSE chunk
                }
            }
        } catch (java.io.IOException e) {
            throw new OpenAIService.OpenAIException(503, "The AI stream was interrupted.");
        }
        if (full.length() == 0) {
            throw new OpenAIService.OpenAIException(502, "The AI service returned an empty response.");
        }
    }

    private ObjectNode requestBody(JsonNode contents, String instructions) {
        ObjectNode body = mapper.createObjectNode();
        if (instructions != null && !instructions.isBlank()) {
            body.putObject("system_instruction").putArray("parts").addObject().put("text", instructions);
        }
        body.set("contents", contents);
        body.putObject("generationConfig").put("maxOutputTokens", OpenAIConfig.get().maxOutputTokens);
        return body;
    }

    private HttpResponse<String> send(ObjectNode body) throws OpenAIService.OpenAIException {
        String key = geminiKey();
        String model = geminiModel();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + model + ":generateContent?key=" + key))
                .timeout(OpenAIConfig.get().timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OpenAIService.OpenAIException(504, "The AI service took too long to respond.");
        } catch (java.io.IOException e) {
            throw new OpenAIService.OpenAIException(503, "The AI service could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAIService.OpenAIException(503, "The request was interrupted.");
        }
    }

    private static String geminiKey() throws OpenAIService.OpenAIException {
        String key = firstNonBlank(System.getenv("GEMINI_API_KEY"), envValue("GEMINI_API_KEY"));
        if (key == null || key.isBlank() || key.contains("YOUR_")) {
            throw new OpenAIService.OpenAIException(503, "AI service is not configured (missing GEMINI_API_KEY).");
        }
        return key;
    }

    private static String geminiModel() {
        return firstNonBlank(System.getenv("GEMINI_MODEL"), envValue("GEMINI_MODEL"), "gemini-3.1-flash-lite-preview");
    }

    private void checkStatus(int code, String body) throws OpenAIService.OpenAIException {
        if (code == 400 || code == 401 || code == 403) {
            throw new OpenAIService.OpenAIException(502, "The AI service credentials need administrator attention.");
        }
        if (code == 429) {
            throw new OpenAIService.OpenAIException(429, "The AI service is temporarily rate limited. Please try again later.");
        }
        if (code == 404) {
            throw new OpenAIService.OpenAIException(502, "The configured AI model was not found for this API project.");
        }
        if (code == 503) {
            throw new OpenAIService.OpenAIException(503, "The AI service is temporarily unavailable. Please try again.");
        }
        if (code < 200 || code >= 300) {
            throw new OpenAIService.OpenAIException(502, "The AI service could not complete this request (HTTP " + code + ").");
        }
    }

    private String extractText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode cand : root.path("candidates")) {
            for (JsonNode part : cand.path("content").path("parts")) {
                String t = part.path("text").asText("");
                if (!t.isBlank()) sb.append(t);
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("empty output");
        }
        return text;
    }

    private static String envValue(String key) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(".env");
            if (java.nio.file.Files.isRegularFile(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.startsWith(key + "=")) {
                        return t.substring(key.length() + 1).trim();
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
