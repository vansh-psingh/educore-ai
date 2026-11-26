package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-question attempt responses - the data foundation for mistake
 * intelligence, revision scheduling, readiness and learning analytics.
 *
 * Every answered question (quiz, exam, essay) is stored as one row-like JSON
 * node in attempt_responses.json (backed by the attempt_responses table when
 * PostgreSQL is configured). Classification is rule-based and deterministic -
 * no AI call is made for routine analysis.
 */
public final class Responses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int KEEP = 5000; // bound store growth

    private Responses() {
    }

    /** Error categories: incomplete | calculation | conceptual | procedural | correct | essay */
    public static final String E_INCOMPLETE = "incomplete";
    public static final String E_CALCULATION = "calculation";
    public static final String E_CONCEPTUAL = "conceptual";
    public static final String E_PROCEDURAL = "procedural";
    public static final String E_ESSAY = "essay";

    /**
     * Rule-based mistake classification. Pure heuristics - explainable and
     * cheap. Order matters: blank -> incomplete; numeric mismatch ->
     * calculation; partial keyword overlap on text answers -> procedural;
     * everything else wrong -> conceptual.
     */
    public static String classify(String qType, String given, String correct, boolean ok) {
        if (ok) return "correct";
        String g = given == null ? "" : given.trim();
        String c = correct == null ? "" : correct.trim();
        if (g.isEmpty()) return E_INCOMPLETE;
        if ("long_answer".equals(qType) || "essay".equals(qType)) return E_ESSAY;

        boolean correctNumeric = c.matches(".*\\d.*") && c.replaceAll("[^0-9.\\-]", "").length() >= 2;
        boolean givenNumeric = g.matches(".*\\d.*");
        if (correctNumeric && givenNumeric) {
            // both sides numeric but not equal -> calculation slip
            return E_CALCULATION;
        }
        if (correctNumeric && !givenNumeric) {
            // numeric answer given as words / missing number -> procedural
            return E_PROCEDURAL;
        }

        if ("short_answer".equals(qType) || "fill_blank".equals(qType)) {
            double overlap = keywordOverlap(g, c);
            if (overlap >= 0.25) return E_PROCEDURAL; // right ideas, wrong form/precision
            return E_CONCEPTUAL;
        }
        // mcq / true_false wrong -> conceptual misunderstanding
        return E_CONCEPTUAL;
    }

    /** Fraction of significant keywords from `correct` present in `given` (0..1). */
    public static double keywordOverlap(String given, String correct) {
        String[] kw = correct.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        int total = 0, hits = 0;
        for (String w : kw) {
            if (w.length() < 4) continue;
            total++;
            if (given.toLowerCase(Locale.ROOT).contains(w)) hits++;
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    /** Records one answered question. Never throws - analytics must not break flows. */
    public static void record(AuthService.AuthUser user, String source, String refId,
                              String topic, String subject, String qType, String question,
                              String given, String correct, int marks, int earned,
                              boolean correctFlag, String errorType) {
        flush(List.of(draft(user.id(), source, refId, topic, subject, qType, question,
                given, correct, marks, earned, correctFlag, errorType)));
    }

    /** Convenience: record + auto-classify in one call. */
    public static void recordScored(AuthService.AuthUser user, String source, String refId,
                                    String topic, String subject, String qType, String question,
                                    String given, String correct, int marks, int earned,
                                    boolean correctFlag) {
        record(user, source, refId, topic, subject, qType, question,
                given, correct, marks, earned, correctFlag,
                correctFlag ? "correct" : classify(qType, given, correct, false));
    }

    /** Builds one response node without persisting - for batch submit flows. */
    public static ObjectNode draftScored(AuthService.AuthUser user, String source, String refId,
                                         String topic, String subject, String qType, String question,
                                         String given, String correct, int marks, int earned,
                                         boolean correctFlag) {
        return draft(user.id(), source, refId, topic, subject, qType, question,
                given, correct, marks, earned, correctFlag,
                correctFlag ? "correct" : classify(qType, given, correct, false));
    }

    private static ObjectNode draft(String userId, String source, String refId,
                                    String topic, String subject, String qType, String question,
                                    String given, String correct, int marks, int earned,
                                    boolean correctFlag, String errorType) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("id", java.util.UUID.randomUUID().toString().substring(0, 10));
        r.put("user", userId);
        r.put("source", source);
        r.put("ref", refId == null ? "" : refId);
        r.put("topic", topic == null ? "" : topic);
        r.put("subject", subject == null ? "" : subject);
        r.put("type", qType == null ? "" : qType);
        r.put("question", question == null ? "" : question);
        r.put("given", given == null ? "" : given);
        r.put("correct", correct == null ? "" : correct);
        r.put("marks", marks);
        r.put("earned", earned);
        r.put("correct_flag", correctFlag ? 1 : 0);
        r.put("error_type", errorType == null ? "" : errorType);
        r.put("created", Instant.now().toString());
        return r;
    }

    /**
     * Persists many drafted responses in ONE store read+write. Exam/quiz
     * submits call this once instead of rewriting the whole file per question.
     * Never throws - analytics must not break flows.
     */
    public static void flush(List<ObjectNode> drafts) {
        if (drafts == null || drafts.isEmpty()) return;
        try {
            ObjectNode root = Store.obj(Store.read("attempt_responses.json"));
            ArrayNode items = root.path("items").isArray()
                    ? (ArrayNode) root.path("items") : root.putArray("items");
            for (ObjectNode r : drafts) items.add(r);
            while (items.size() > KEEP) items.remove(0);
            Store.write("attempt_responses.json", root);
        } catch (Exception e) {
            System.err.println("[responses] flush failed: " + e.getMessage());
        }
    }

    /** All responses for a user (admin sees all when caller passes null user filter). */
    public static List<JsonNode> forUser(String userId) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode items = Store.read("attempt_responses.json").path("items");
        if (!items.isArray()) return out;
        for (JsonNode r : items) {
            String owner = r.path("user").asText("");
            if (userId == null || owner.isEmpty() || owner.equals(userId)) out.add(r);
        }
        return out;
    }
}
