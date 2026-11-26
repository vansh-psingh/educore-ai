package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exam Readiness + Learning DNA + Concept Map - all computed from real
 * recorded activity (attempt_responses, exam_history, revision_items,
 * planner_tasks). No fabricated metrics; readiness is an explainable
 * heuristic, explicitly NOT a prediction of exam results.
 */
public final class InsightsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String READINESS_DISCLAIMER =
            "Readiness is an indicative score computed from your recorded quiz/exam "
                    + "activity and revision progress. It is not a prediction of exam results.";

    private InsightsService() {
    }

    // ================= exam readiness =================

    public static ObjectNode readiness(AuthService.AuthUser user) {
        List<JsonNode> responses = Responses.forUser(user.id());
        ObjectNode out = MAPPER.createObjectNode();
        out.put("disclaimer", READINESS_DISCLAIMER);

        if (responses.isEmpty()) {
            out.put("score", 0);
            out.put("label", "No data yet");
            out.put("coverage_pct", 0);
            out.put("accuracy_pct", 0);
            out.put("revision_pct", 0);
            out.put("activity_score", 0);
            out.set("components", MAPPER.createObjectNode());
            out.putArray("attention_topics");
            out.putArray("strong_topics");
            return out;
        }

        // topic coverage: attempted topics vs topics seen in planner/notes seeds
        Map<String, int[]> topicStats = new LinkedHashMap<>(); // topic -> [attempts, earned, marks]
        Map<String, String> topicSubject = new HashMap<>();
        for (JsonNode r : responses) {
            String topic = nz(r.path("topic").asText("General"));
            int[] s = topicStats.computeIfAbsent(topic, k -> new int[3]);
            s[0]++;
            s[1] += r.path("earned").asInt(0);
            s[2] += r.path("marks").asInt(1);
            topicSubject.putIfAbsent(topic, nz(r.path("subject").asText("General")));
        }

        // accuracy across all responses
        int earned = 0, marks = 0, attempts = 0, correctN = 0;
        for (JsonNode r : responses) {
            earned += r.path("earned").asInt(0);
            marks += r.path("marks").asInt(1);
            attempts++;
            if (r.path("correct_flag").asInt(0) == 1) correctN++;
        }
        int accuracy = marks == 0 ? 0 : (int) Math.round(100.0 * earned / marks);

        // breadth: distinct topics attempted (cap contribution at 8 topics)
        int distinctTopics = topicStats.size();
        int coverage = Math.min(100, distinctTopics * 12); // ~8 topics = full breadth credit

        // revision completion
        JsonNode rev = RevisionService.dashboard(user);
        int revTotal = rev.path("total").asInt(0);
        int revDone = rev.path("completed").size();
        int revisionPct = revTotal == 0 ? 0 : (int) Math.round(100.0 * revDone / revTotal);

        // recency: responses in last 7 days
        Instant weekAgo = Instant.now().minus(7 * 86400L, java.time.temporal.ChronoUnit.SECONDS);
        long recent = responses.stream()
                .filter(r -> Instant.parse(r.path("created").asText(Instant.EPOCH.toString()))
                        .isAfter(weekAgo))
                .count();
        int activity = attempts == 0 ? 0 : (int) Math.min(100, 40 + recent * 15);

        // weighted composite - explainable components
        int score = (int) Math.round(coverage * 0.30 + accuracy * 0.45 + revisionPct * 0.15 + activity * 0.10);
        score = Math.max(0, Math.min(100, score));

        ObjectNode comp = out.putObject("components");
        comp.put("topic_coverage_pct", coverage);
        comp.put("accuracy_pct", accuracy);
        comp.put("revision_completion_pct", revisionPct);
        comp.put("recent_activity_score", activity);
        comp.put("weighting", "coverage 30% + accuracy 45% + revision 15% + activity 10%");

        out.put("score", score);
        out.put("label", score >= 75 ? "Well prepared" : score >= 50 ? "Building readiness" : "Needs focus");
        out.put("coverage_pct", coverage);
        out.put("accuracy_pct", accuracy);
        out.put("revision_pct", revisionPct);
        out.put("attempts", attempts);
        out.put("responses_7d", recent);

        // topics needing attention: lowest topic accuracy with >=2 attempts
        ArrayNode attention = out.putArray("attention_topics");
        topicStats.entrySet().stream()
                .filter(e -> e.getValue()[0] >= 2)
                .map(e -> {
                    int[] s = e.getValue();
                    int pct = s[2] == 0 ? 0 : (int) Math.round(100.0 * s[1] / s[2]);
                    return new Object[]{e.getKey(), pct};
                })
                .sorted(Comparator.comparingInt(a -> (Integer) a[1]))
                .limit(5)
                .forEach(a -> {
                    ObjectNode t = attention.addObject();
                    t.put("topic", (String) a[0]);
                    t.put("subject", topicSubject.getOrDefault((String) a[0], "General"));
                    t.put("accuracy_pct", (Integer) a[1]);
                });

        ArrayNode strong = out.putArray("strong_topics");
        topicStats.entrySet().stream()
                .filter(e -> e.getValue()[0] >= 2)
                .map(e -> {
                    int[] s = e.getValue();
                    int pct = s[2] == 0 ? 0 : (int) Math.round(100.0 * s[1] / s[2]);
                    return new Object[]{e.getKey(), pct};
                })
                .sorted(Comparator.comparingInt((Object[] a) -> (Integer) a[1]).reversed())
                .limit(5)
                .forEach(a -> {
                    ObjectNode t = strong.addObject();
                    t.put("topic", (String) a[0]);
                    t.put("accuracy_pct", (Integer) a[1]);
                });
        return out;
    }

    // ================= learning DNA =================

    public static ObjectNode dna(AuthService.AuthUser user) {
        List<JsonNode> responses = Responses.forUser(user.id());
        ObjectNode out = MAPPER.createObjectNode();

        // subject-wise performance + trend (first half vs second half of activity)
        Map<String, List<JsonNode>> bySubject = new LinkedHashMap<>();
        for (JsonNode r : responses) {
            bySubject.computeIfAbsent(nz(r.path("subject").asText("General")), k -> new ArrayList<>()).add(r);
        }
        ArrayNode subjects = out.putArray("subjects");
        for (Map.Entry<String, List<JsonNode>> e : bySubject.entrySet()) {
            List<JsonNode> rs = e.getValue();
            int earned = 0, marks = 0, n = 0;
            for (JsonNode r : rs) { earned += r.path("earned").asInt(0); marks += r.path("marks").asInt(1); n++; }
            int acc = marks == 0 ? 0 : (int) Math.round(100.0 * earned / marks);
            // trend: split chronologically (list is insertion-ordered)
            int trend = 0;
            if (n >= 4) {
                int half = n / 2;
                int e1 = 0, m1 = 0, e2 = 0, m2 = 0;
                for (int i = 0; i < half; i++) { e1 += rs.get(i).path("earned").asInt(0); m1 += rs.get(i).path("marks").asInt(1); }
                for (int i = half; i < n; i++) { e2 += rs.get(i).path("earned").asInt(0); m2 += rs.get(i).path("marks").asInt(1); }
                int a1 = m1 == 0 ? 0 : (int) Math.round(100.0 * e1 / m1);
                int a2 = m2 == 0 ? 0 : (int) Math.round(100.0 * e2 / m2);
                trend = a2 - a1;
            }
            ObjectNode s = subjects.addObject();
            s.put("subject", e.getKey());
            s.put("attempts", n);
            s.put("accuracy_pct", acc);
            s.put("trend_pct", trend);
        }

        // mistake frequency by type
        Map<String, Integer> errFreq = new LinkedHashMap<>();
        for (JsonNode r : responses) {
            if (r.path("correct_flag").asInt(0) != 1) {
                errFreq.merge(r.path("error_type").asText("conceptual"), 1, Integer::sum);
            }
        }
        ObjectNode mistakes = out.putObject("mistake_frequency");
        errFreq.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(en -> mistakes.put(en.getKey(), en.getValue()));

        // consistency: active days in last 14 days + total span
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        Instant earliest = null, latest = null;
        for (JsonNode r : responses) {
            try {
                Instant t = Instant.parse(r.path("created").asText());
                LocalDate d = t.atZone(ZoneOffset.UTC).toLocalDate();
                perDay.merge(d, 1, Integer::sum);
                if (earliest == null || t.isBefore(earliest)) earliest = t;
                if (latest == null || t.isAfter(latest)) latest = t;
            } catch (Exception ignored) { }
        }
        int active14 = 0;
        for (int i = 0; i < 14; i++) {
            if (perDay.containsKey(today.minusDays(i))) active14++;
        }
        long spanDays = earliest != null && latest != null
                ? Math.max(1, ChronoUnit.DAYS.between(earliest, latest)) : 1;
        ObjectNode consistency = out.putObject("consistency");
        consistency.put("active_days_14d", active14);
        consistency.put("consistency_pct", (int) Math.round(100.0 * active14 / 14));
        consistency.put("activity_span_days", spanDays);
        consistency.put("responses_per_day", Math.round(responses.size() * 10.0 / spanDays) / 10.0);

        // topic mastery: per-topic accuracy (top 12 by attempts)
        Map<String, int[]> topicAgg = new LinkedHashMap<>();
        for (JsonNode r : responses) {
            String t = nz(r.path("topic").asText("General"));
            int[] a = topicAgg.computeIfAbsent(t, k -> new int[3]);
            a[0]++;
            a[1] += r.path("earned").asInt(0);
            a[2] += r.path("marks").asInt(1);
        }
        ArrayNode mastery = out.putArray("topic_mastery");
        topicAgg.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(12)
                .forEach(en -> {
                    int[] a = en.getValue();
                    ObjectNode t = mastery.addObject();
                    t.put("topic", en.getKey());
                    t.put("attempts", a[0]);
                    t.put("mastery_pct", a[2] == 0 ? 0 : (int) Math.round(100.0 * a[1] / a[2]));
                });

        // recommendations from real data only
        ArrayNode recs = out.putArray("recommendations");
        errFreq.entrySet().stream().findFirst().ifPresent(e -> {
            if (e.getValue() >= 3) {
                ObjectNode rc = recs.addObject();
                rc.put("type", "focus");
                rc.put("text", "Your most frequent error type is '" + e.getKey()
                        + "' (" + e.getValue() + " occurrences) - review explanations for missed questions.");
            }
        });
        if (active14 <= 3 && !responses.isEmpty()) {
            ObjectNode rc = recs.addObject();
            rc.put("type", "consistency");
            rc.put("text", "You were active on only " + active14 + " of the last 14 days - short daily sessions beat long rare ones.");
        }
        subjects.forEach(s -> {
            if (s.path("trend_pct").asInt(0) <= -15 && recs.size() < 4) {
                ObjectNode rc = recs.addObject();
                rc.put("type", "subject");
                rc.put("text", "Your " + s.path("subject").asText() + " accuracy dropped "
                        + Math.abs(s.path("trend_pct").asInt()) + " points recently - schedule a revision session.");
            }
        });

        out.put("total_responses", responses.size());
        return out;
    }

    // ================= concept map =================

    /**
     * Concept map built ONLY from the student's real data: subjects and
     * topics that actually appear in their responses/planner. Edges connect
     * subject -> topic and topics within a subject that co-occur in the same
     * attempt. No invented academic prerequisites.
     */
    public static ObjectNode conceptMap(AuthService.AuthUser user) {
        List<JsonNode> responses = Responses.forUser(user.id());
        ObjectNode out = MAPPER.createObjectNode();
        out.put("note", "Map shows only topics from your recorded activity - not a full curriculum.");

        // subject -> topics with stats
        Map<String, Map<String, int[]>> tree = new LinkedHashMap<>(); // subject -> topic -> [attempts, earned, marks]
        Map<String, String> topicSubject = new HashMap<>();
        for (JsonNode r : responses) {
            String subject = nz(r.path("subject").asText("General"));
            String topic = nz(r.path("topic").asText("General"));
            int[] a = tree.computeIfAbsent(subject, k -> new LinkedHashMap<>())
                    .computeIfAbsent(topic, k -> new int[3]);
            a[0]++;
            a[1] += r.path("earned").asInt(0);
            a[2] += r.path("marks").asInt(1);
            topicSubject.put(topic, subject);
        }
        // include planner subjects/topics as pending nodes (real user data)
        JsonNode tasks = Store.read("planner_tasks.json").path("tasks");
        if (tasks.isArray()) {
            for (JsonNode t : tasks) {
                String subject = nz(t.path("subject").asText("General"));
                String title = t.path("title").asText("");
                if (title.isEmpty()) continue;
                String topic = title.replaceFirst("(?i)^revise:\\s*", "");
                tree.computeIfAbsent(subject, k -> new LinkedHashMap<>())
                        .computeIfAbsent(topic, k -> new int[3]);
            }
        }

        ArrayNode nodes = out.putArray("nodes");
        ArrayNode edges = out.putArray("edges");
        int idx = 0;
        Map<String, Integer> nodeIdx = new HashMap<>();

        for (Map.Entry<String, Map<String, int[]>> sub : tree.entrySet()) {
            String subject = sub.getKey();
            int sIdx = idx++;
            nodeIdx.put("s:" + subject, sIdx);
            ObjectNode sn = nodes.addObject();
            sn.put("id", "s:" + subject);
            sn.put("label", subject);
            sn.put("type", "subject");
            sn.put("x", 0); sn.put("y", 0); // client lays out

            for (Map.Entry<String, int[]> te : sub.getValue().entrySet()) {
                int[] a = te.getValue();
                int acc = a[2] == 0 ? -1 : (int) Math.round(100.0 * a[1] / a[2]);
                String status = a[0] == 0 ? "pending" : acc >= 70 ? "completed" : acc >= 40 ? "weak" : "pending";
                String tId = "t:" + subject + ":" + te.getKey();
                nodeIdx.put(tId, idx);
                ObjectNode tn = nodes.addObject();
                tn.put("id", tId);
                tn.put("label", te.getKey());
                tn.put("type", "topic");
                tn.put("subject", subject);
                tn.put("status", status);
                tn.put("attempts", a[0]);
                tn.put("accuracy_pct", Math.max(0, acc));
                tn.put("x", 0); tn.put("y", 0);

                ObjectNode e = edges.addObject();
                e.put("from", "s:" + subject);
                e.put("to", tId);
                e.put("kind", "contains");
            }
        }

        // co-occurrence edges: topics answered in the same attempt share a ref
        Map<String, List<String>> byRef = new HashMap<>();
        for (JsonNode r : responses) {
            String ref = r.path("ref").asText("");
            if (ref.isEmpty()) continue;
            String tId = "t:" + nz(r.path("subject").asText("General")) + ":" + nz(r.path("topic").asText("General"));
            byRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(tId);
        }
        java.util.Set<String> seenEdge = new java.util.HashSet<>();
        for (List<String> ids : byRef.values()) {
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                    String a = ids.get(i), b = ids.get(j);
                    if (a.equals(b)) continue;
                    String ek = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                    if (!seenEdge.add(ek)) continue;
                    if (!nodeIdx.containsKey(a) || !nodeIdx.containsKey(b)) continue;
                    ObjectNode e = edges.addObject();
                    e.put("from", a); e.put("to", b); e.put("kind", "related");
                }
            }
        }

        // recommended next: pending/weak topics with fewest attempts
        ArrayNode recommended = out.putArray("recommended_next");
        List<ObjectNode> cands = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (!"topic".equals(n.path("type").asText())) continue;
            String st = n.path("status").asText();
            if ("completed".equals(st)) continue;
            cands.add((ObjectNode) n);
        }
        cands.sort(Comparator.comparingInt(n -> n.path("attempts").asInt(0)));
        for (int i = 0; i < Math.min(6, cands.size()); i++) {
            ObjectNode c = cands.get(i);
            recommended.addObject()
                    .put("topic", c.path("label").asText())
                    .put("subject", c.path("subject").asText())
                    .put("status", c.path("status").asText());
        }
        return out;
    }

    private static String nz(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? "General" : t;
    }
}
