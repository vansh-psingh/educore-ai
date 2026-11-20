package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Async-capable OpenAI client using the Responses API.
 * The API key is read from backend configuration only.
 */
public final class OpenAIService {

    public static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAIService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Checked exception carrying an HTTP status for the API layer to map. */
    public static final class OpenAIException extends Exception {
        public final int status;

        public OpenAIException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Generates text via the OpenAI Responses API.
     *
     * @param instructions system-level instructions
     * @param input        user input text
     * @return generated text
     */
    public String generate(String instructions, String input) throws OpenAIException {
        OpenAIConfig cfg = OpenAIConfig.get();
        if (!cfg.configured) {
            throw new OpenAIException(503, "AI service is not configured (missing OPENAI_API_KEY).");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.model);
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("max_output_tokens", cfg.maxOutputTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RESPONSES_URL))
                .timeout(cfg.timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OpenAIException(504, "The AI service took too long to respond.");
        } catch (java.io.IOException e) {
            throw new OpenAIException(503, "The AI service could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAIException(503, "The request was interrupted.");
        }

        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new OpenAIException(502, "The AI service credentials need administrator attention.");
        }
        if (code == 429) {
            throw new OpenAIException(429, "The AI service is temporarily rate limited. Please try again later.");
        }
        if (code == 404) {
            throw new OpenAIException(502, "The configured AI model was not found for this API project.");
        }
        if (code < 200 || code >= 300) {
            throw new OpenAIException(502, "The AI service could not complete this request (HTTP " + code + ").");
        }

        try {
            return extractText(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new OpenAIException(502, "Could not parse the AI service response.");
        }
    }

    /** Extracts assistant text from a Responses API payload. */
    private String extractText(JsonNode root) {
        // convenience field when present
        JsonNode direct = root.path("output_text");
        if (direct.isTextual() && !direct.asText().isBlank()) {
            return direct.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    sb.append(content.path("text").asText());
                }
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("empty output");
        }
        return text;
    }
}
