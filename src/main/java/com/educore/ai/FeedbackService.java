package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Feedback loop: stores per-user ratings on AI answers and surfaces the
 * user's own highly-rated Q&A pairs as few-shot examples for future prompts.
 * Data persists in data/store/feedback.json.
 */
public final class FeedbackService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ITEMS = 500;
    private static final int EXAMPLE_ANSWER_CHARS = 1500;

    private FeedbackService() {
    }

    private static ArrayNode items() {
        JsonNode f = Store.read("feedback.json").path("items");
        return f.isArray() ? (ArrayNode) f : MAPPER.createArrayNode();
    }

    private static void save(ArrayNode items) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("items", items);
        Store.write("feedback.json", root);
    }

    public static ObjectNode submit(AuthService.AuthUser user, JsonNode req) {
        int rating = Math.max(1, Math.min(5, req.path("rating").asInt(0)));
        if (rating == 0) throw new IllegalArgumentException("rating (1-5) is required");

        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", UUID.randomUUID().toString().substring(0, 8));
        item.put("user", user.id());
        item.put("rating", rating);
        item.put("comment", trunc(req.path("comment").asText(""), 1000));
        item.put("question", trunc(req.path("question").asText(""), 2000));
        item.put("answer", trunc(req.path("answer").asText(""), EXAMPLE_ANSWER_CHARS));
        item.put("created", java.time.Instant.now().toString());

        ArrayNode all = items();
        all.add(item);
        while (all.size() > MAX_ITEMS) all.remove(0);
        save(all);
        return item;
    }

    public static ObjectNode stats(AuthService.AuthUser user) {
        int count = 0, sum = 0, up = 0, down = 0;
        for (JsonNode i : items()) {
            if (!i.path("user").asText().equals(user.id())) continue;
            count++;
            sum += i.path("rating").asInt();
            if (i.path("rating").asInt() >= 4) up++;
            if (i.path("rating").asInt() <= 2) down++;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("total", count);
        out.put("average_rating", count == 0 ? 0 : Math.round(100.0 * sum / count) / 100.0);
        out.put("positive", up);
        out.put("negative", down);
        return out;
    }

    /**
     * Returns the user's own highest-rated Q&A pairs (rating >= 4) for use as
     * few-shot style examples. Never returns other users' data.
     */
    public static List<JsonNode> topExamples(String userId, int max) {
        List<JsonNode> good = new ArrayList<>();
        for (JsonNode i : items()) {
            if (i.path("user").asText().equals(userId)
                    && i.path("rating").asInt() >= 4
                    && !i.path("question").asText().isBlank()
                    && !i.path("answer").asText().isBlank()) {
                good.add(i);
            }
        }
        good.sort(Comparator.comparingInt((JsonNode i) -> i.path("rating").asInt()).reversed());
        return good.subList(0, Math.min(max, good.size()));
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Builds a prompt suffix of liked examples, or "" when none exist. */
    public static String examplesPrompt(String userId, int max) {
        List<JsonNode> ex = topExamples(userId, max);
        if (ex.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\n\nThe student previously rated these answers highly - match their preferred style "
                        + "(clarity, structure, depth, language):\n");
        for (JsonNode i : ex) {
            sb.append("\nQ: ").append(i.path("question").asText())
                    .append("\nGood answer style: ").append(i.path("answer").asText())
                    .append("\n---\n");
        }
        return sb.toString();
    }
}
