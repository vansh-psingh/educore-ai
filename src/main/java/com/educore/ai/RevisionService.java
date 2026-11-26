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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart Revision Scheduler - lightweight spaced-repetition (SM-2 style)
 * over the student's real weak topics. Pure algorithm, no AI calls.
 *
 * State per (subject, topic): ease factor, interval days, reps, lapses,
 * due date, status. Seeded automatically from mistake/topic weakness and
 * can be reviewed manually ("mark as revised") or auto-adjusted when new
 * attempt responses arrive for that topic.
 */
public final class RevisionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INTERVAL_DAYS = 60;
    private static final double MIN_EASE = 1.3;
    private static final double MAX_EASE = 3.5;

    private RevisionService() {
    }

    // ================= reads =================

    public static ObjectNode dashboard(AuthService.AuthUser user) {
        List<JsonNode> items = itemsFor(user.id());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ArrayNode due = MAPPER.createArrayNode();
        ArrayNode upcoming = MAPPER.createArrayNode();
        ArrayNode done = MAPPER.createArrayNode();
        int overdueN = 0;

        for (JsonNode it : items) {
            String status = it.path("status").asText("pending");
            if ("mastered".equals(status)) { done.add(it); continue; }
            LocalDate d = parseDate(it.path("due").asText(""));
            if (d == null || !d.isAfter(today)) {
                overdueN++;
                due.add(it);
            } else if (ChronoUnit.DAYS.between(today, d) <= 3) {
                due.add(it); // due soon counts as due
            } else {
                upcoming.add(it);
            }
        }
        sortByDue(due);
        sortByDue(upcoming);

        ObjectNode out = MAPPER.createObjectNode();
        out.set("due", due);
        out.set("upcoming", upcoming);
        out.set("completed", done);
        out.put("overdue", overdueN);
        out.put("total", items.size());
        out.put("pending", due.size() + upcoming.size());
        return out;
    }

    // ================= seeding =================

    /**
     * Seeds revision items from the student's weak topics (mistake analysis).
     * Idempotent - existing topics are kept, only missing ones are created.
     * Returns number of items created.
     */
    public static int seedFromWeakTopics(AuthService.AuthUser user) {
        ObjectNode analysis = MistakeService.analyze(user);
        JsonNode topics = analysis.path("topics");
        if (!topics.isArray()) return 0;

        Map<String, JsonNode> existing = new LinkedHashMap<>();
        for (JsonNode it : itemsFor(user.id())) {
            existing.put(keyOf(it.path("subject").asText(""), it.path("topic").asText("")), it);
        }

        int created = 0;
        for (JsonNode t : topics) {
            int errors = t.path("errors").asInt(0);
            if (errors == 0) continue;
            String topic = t.path("topic").asText("General");
            String subject = t.path("subject").asText("General");
            String k = keyOf(subject, topic);
            if (existing.containsKey(k)) continue;

            // first interval: weak topics start tomorrow, very weak today
            int interval = t.path("score_pct").asInt(0) < 40 ? 0 : 1;
            ObjectNode item = baseItem(user.id(), subject, topic);
            item.put("interval", interval);
            item.put("due", LocalDate.now(ZoneOffset.UTC).plusDays(interval).toString());
            item.put("reason", "weak_topic:" + errors + "_errors");
            upsert(item);
            created++;
        }
        return created;
    }

    // ================= review / update =================

    /**
     * SM-2-lite update. quality 0-5 (self-assessed or derived):
     *   >=3 good recall, <3 poor recall.
     * Adjusts ease, interval, reps, lapses, due date and status.
     */
    public static JsonNode review(String id, AuthService.AuthUser user, int quality) {
        JsonNode item = find(user.id(), id);
        if (item == null) throw new IllegalArgumentException("revision item not found");
        if ("mastered".equals(item.path("status").asText())) {
            throw new IllegalArgumentException("topic already mastered");
        }

        ObjectNode it = (ObjectNode) item.deepCopy();
        double ease = parseDouble(it.path("ease").asText("2.5"), 2.5);
        int interval = it.path("interval").asInt(1);
        int reps = it.path("reps").asInt(0);
        int lapses = it.path("lapses").asInt(0);

        boolean good = quality >= 3;
        if (good) {
            reps++;
            interval = reps <= 1 ? 1 : reps == 2 ? 3 : Math.min(MAX_INTERVAL_DAYS, (int) Math.round(interval * ease));
            ease = Math.min(MAX_EASE, ease + 0.1);
        } else {
            lapses++;
            reps = 0;
            interval = 1; // back to short cycle
            ease = Math.max(MIN_EASE, ease - 0.2);
        }

        String status = "mastered";
        if (lapses >= 3 || reps < 3) status = "reviewing";
        if (reps >= 4 && lapses == 0) status = "mastered";
        if (reps >= 5) status = "mastered";

        it.put("ease", String.format(java.util.Locale.ROOT, "%.2f", ease));
        it.put("interval", interval);
        it.put("reps", reps);
        it.put("lapses", lapses);
        it.put("status", status);
        it.put("last_review", Instant.now().toString());
        it.put("due", LocalDate.now(ZoneOffset.UTC).plusDays(interval).toString());
        it.put("updated", Instant.now().toString());
        upsert(it);
        return it;
    }

    /** Mark a topic as revised today (manual check-off, quality assumed good). */
    public static JsonNode markRevised(String id, AuthService.AuthUser user) {
        return review(id, user, 4);
    }

    /**
     * Auto-adjust revision items when new attempt responses arrive.
     * Called after quiz/exam submit: a correct answer nudges the topic
     * toward mastery; errors reset its cycle. Cheap and bounded.
     */
    public static void onResponsesRecorded(AuthService.AuthUser user, List<JsonNode> responses) {
        if (responses == null || responses.isEmpty()) return;
        Map<String, TopicAgg> agg = new LinkedHashMap<>();
        for (JsonNode r : responses) {
            String topic = r.path("topic").asText("General");
            if (topic.isEmpty()) topic = "General";
            final String topicF = topic;
            String subject = r.path("subject").asText("General");
            TopicAgg a = agg.computeIfAbsent(subject + "|" + topicF, k -> new TopicAgg(subject, topicF));
            a.total++;
            if (r.path("correct_flag").asInt(0) == 1) a.correct++;
        }
        for (TopicAgg a : agg.values()) {
            JsonNode item = findByTopic(user.id(), a.subject, a.topic);
            if (item == null) continue; // not scheduled - seeding handles it
            double acc = a.total == 0 ? 0 : (double) a.correct / a.total;
            int quality = acc >= 0.8 ? 4 : acc >= 0.5 ? 3 : 1;
            try {
                review(item.path("id").asText(), user, quality);
            } catch (Exception ignored) {
            }
        }
    }

    // ================= planner integration =================

    /** Creates (or returns existing) planner task for a revision item. */
    public static ObjectNode toPlannerTask(String id, AuthService.AuthUser user) {
        JsonNode item = find(user.id(), id);
        if (item == null) throw new IllegalArgumentException("revision item not found");
        String topic = item.path("topic").asText("Topic");
        String subject = item.path("subject").asText("General");
        String due = item.path("due").asText(LocalDate.now(ZoneOffset.UTC).toString());

        // avoid duplicates: same title+subject pending task
        JsonNode tasks = Store.read("planner_tasks.json").path("tasks");
        if (tasks.isArray()) {
            for (JsonNode t : tasks) {
                if (t.path("title").asText("").equalsIgnoreCase("Revise: " + topic)
                        && t.path("subject").asText("").equalsIgnoreCase(subject)
                        && "pending".equals(t.path("status").asText("pending"))) {
                    return (ObjectNode) t;
                }
            }
        }
        ObjectNode req = MAPPER.createObjectNode();
        req.put("title", "Revise: " + topic);
        req.put("subject", subject);
        req.put("due_date", due);
        req.put("priority", "high");
        req.put("description", "Spaced-repetition revision (interval "
                + item.path("interval").asInt(1) + "d, reps " + item.path("reps").asInt(0) + ")");
        req.put("minutes", 30);
        return PlannerService.create(req, user);
    }

    // ================= storage helpers =================

    private static List<JsonNode> itemsFor(String userId) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode items = Store.read("revision_items.json").path("items");
        if (!items.isArray()) return out;
        for (JsonNode it : items) {
            String owner = it.path("user").asText("");
            if (owner.isEmpty() || owner.equals(userId)) out.add(it);
        }
        return out;
    }

    private static JsonNode find(String userId, String id) {
        for (JsonNode it : itemsFor(userId)) {
            if (it.path("id").asText("").equals(id)) return it;
        }
        return null;
    }

    private static JsonNode findByTopic(String userId, String subject, String topic) {
        for (JsonNode it : itemsFor(userId)) {
            if (it.path("subject").asText("").equalsIgnoreCase(subject)
                    && it.path("topic").asText("").equalsIgnoreCase(topic)) return it;
        }
        return null;
    }

    private static ObjectNode baseItem(String userId, String subject, String topic) {
        ObjectNode it = MAPPER.createObjectNode();
        it.put("id", java.util.UUID.randomUUID().toString().substring(0, 10));
        it.put("user", userId);
        it.put("subject", subject.isEmpty() ? "General" : subject);
        it.put("topic", topic.isEmpty() ? "General" : topic);
        it.put("status", "pending");
        it.put("ease", "2.50");
        it.put("interval", 1);
        it.put("reps", 0);
        it.put("lapses", 0);
        it.put("due", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString());
        it.put("created", Instant.now().toString());
        it.put("updated", Instant.now().toString());
        return it;
    }

    private static void upsert(ObjectNode item) {
        ObjectNode root = Store.obj(Store.read("revision_items.json"));
        ArrayNode items = root.path("items").isArray()
                ? (ArrayNode) root.path("items") : root.putArray("items");
        String id = item.path("id").asText();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path("id").asText("").equals(id)) {
                items.set(i, item);
                Store.write("revision_items.json", root);
                return;
            }
        }
        items.add(item);
        Store.write("revision_items.json", root);
    }

    private static String keyOf(String subject, String topic) {
        return subject.toLowerCase() + "|" + topic.toLowerCase();
    }

    private static LocalDate parseDate(String s) {
        try { return s.isBlank() ? null : LocalDate.parse(s); }
        catch (Exception e) { return null; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static void sortByDue(ArrayNode arr) {
        List<JsonNode> list = new ArrayList<>();
        arr.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.path("due").asText("9999")));
        arr.removeAll();
        list.forEach(arr::add);
    }

    private static final class TopicAgg {
        final String subject, topic;
        int total, correct;
        TopicAgg(String s, String t) { subject = s; topic = t; }
    }
}
