package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mistake Intelligence - rule-based analysis over stored attempt responses.
 * No AI calls: categorization, weakness detection, repetition tracking and
 * practice recommendations are all deterministic aggregates.
 */
public final class MistakeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MistakeService() {
    }

    /** Full mistake-intelligence payload for one user. */
    public static ObjectNode analyze(AuthService.AuthUser user) {
        List<JsonNode> all = Responses.forUser(user.id());
        ObjectNode out = MAPPER.createObjectNode();

        // ---- per-topic aggregates ----
        Map<String, TopicStat> topics = new LinkedHashMap<>();
        Map<String, Integer> errorCounts = new HashMap<>();
        Map<String, Integer> repeatedQuestions = new HashMap<>(); // normalized question -> wrong count
        int total = 0, correctN = 0;

        for (JsonNode r : all) {
            total++;
            boolean ok = r.path("correct_flag").asInt(0) == 1;
            if (ok) correctN++;
            String topic = norm(r.path("topic").asText("General"));
            String err = r.path("error_type").asText(ok ? "correct" : "conceptual");
            TopicStat ts = topics.computeIfAbsent(topic, k -> new TopicStat(topic));
            if ("General".equals(ts.subject) && !r.path("subject").asText("").isEmpty()) {
                ts.subject = r.path("subject").asText();
            }
            ts.attempts++;
            ts.marks += r.path("marks").asInt(1);
            ts.earned += r.path("earned").asInt(0);
            if (ok) ts.correct++;
            else {
                ts.errors++;
                ts.errorTypes.merge(err, 1, Integer::sum);
                ts.lastWrongAt = max(ts.lastWrongAt, r.path("created").asText(""));
                // repeated-mistake detection: same question text wrong again
                String qkey = normalizeQuestion(r.path("question").asText(""));
                if (!qkey.isEmpty()) {
                    int c = repeatedQuestions.merge(qkey, 1, Integer::sum);
                    if (c >= 2) ts.repeated++;
                }
            }
            if (!ok) errorCounts.merge(err, 1, Integer::sum);
            // improvement signal: score trend inside the topic (first half vs second half)
            ts.recent.add(ok);
            if (ts.recent.size() > 60) ts.recent.remove(0);
        }

        // ---- topic list sorted by weakness (errors desc, then score asc) ----
        List<TopicStat> sorted = new ArrayList<>(topics.values());
        sorted.sort(Comparator
                .comparingInt((TopicStat t) -> -t.errors)
                .thenComparingDouble(t -> t.scorePct()));
        ArrayNode topicArr = out.putArray("topics");
        for (TopicStat t : sorted) {
            ObjectNode tn = topicArr.addObject();
            tn.put("topic", t.topic);
            tn.put("subject", t.subject());
            tn.put("attempts", t.attempts);
            tn.put("errors", t.errors);
            tn.put("score_pct", t.scorePct());
            tn.put("repeated_mistakes", t.repeated);
            tn.put("improving", t.improving());
            ObjectNode et = tn.putObject("error_types");
            t.errorTypes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> et.put(e.getKey(), e.getValue()));
            tn.put("status", t.errors == 0 ? "strong" : t.scorePct() < 50 ? "weak" : "developing");
        }

        // ---- error-type summary ----
        ObjectNode errs = out.putObject("error_summary");
        errorCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> errs.put(e.getKey(), e.getValue()));

        // ---- top repeated mistakes (question-level) ----
        ArrayNode rep = out.putArray("repeated_mistakes");
        repeatedQuestions.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> {
                    ObjectNode m = rep.addObject();
                    m.put("question", shorten(e.getKey()));
                    m.put("times_wrong", e.getValue());
                    String topic = topics.keySet().stream()
                            .filter(t -> all.stream().anyMatch(r ->
                                    normalizeQuestion(r.path("question").asText("")).equals(e.getKey())
                                            && norm(r.path("topic").asText("General")).equals(t)))
                            .findFirst().orElse("General");
                    m.put("topic", topic);
                });

        // ---- recommendations (rule-based) ----
        ArrayNode recs = out.putArray("recommendations");
        sorted.stream().filter(t -> t.errors > 0).limit(5).forEach(t -> {
            ObjectNode rc = recs.addObject();
            rc.put("topic", t.topic);
            rc.put("reason", t.repeated > 0
                    ? "Repeated mistakes on this topic - review the concept, then retry practice."
                    : "Errors here are mostly " + t.dominantError() + " - targeted practice recommended.");
            rc.put("action", "practice");
            rc.put("action_label", "Practice " + t.topic);
        });
        if (recs.isEmpty() && total > 0) {
            ObjectNode rc = recs.addObject();
            rc.put("topic", "General");
            rc.put("reason", "No mistakes recorded yet - keep practicing to build mastery data.");
            rc.put("action", "practice");
            rc.put("action_label", "Take a quiz");
        }

        out.put("total_responses", total);
        out.put("total_correct", correctN);
        out.put("accuracy_pct", total == 0 ? 0 : (int) Math.round(100.0 * correctN / total));
        out.put("weak_topics", sorted.stream().filter(t -> t.errors > 0 && t.scorePct() < 60).count());
        return out;
    }

    // ---------- helpers ----------

    private static final class TopicStat {
        final String topic;
        String subject = "General";
        int attempts, errors, correct, repeated, marks, earned;
        final Map<String, Integer> errorTypes = new HashMap<>();
        String lastWrongAt = "";
        final List<Boolean> recent = new ArrayList<>();

        TopicStat(String topic) { this.topic = topic; }

        int scorePct() { return marks == 0 ? 0 : (int) Math.round(100.0 * earned / marks); }

        String subject() {
            // subject is filled by caller via responses; keep first-seen subject
            return subject;
        }

        String dominantError() {
            return errorTypes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("conceptual");
        }

        boolean improving() {
            if (recent.size() < 4) return false;
            int half = recent.size() / 2;
            double first = recent.subList(0, half).stream().filter(Boolean::booleanValue).count() / (double) half;
            double second = recent.subList(half, recent.size()).stream().filter(Boolean::booleanValue).count()
                    / (double) (recent.size() - half);
            return second > first + 0.15;
        }
    }

    private static String norm(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? "General" : t;
    }

    private static String normalizeQuestion(String q) {
        return q == null ? "" : q.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String shorten(String s) {
        return s.length() > 90 ? s.substring(0, 87) + "..." : s;
    }

    private static String max(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
