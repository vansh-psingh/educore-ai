package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

/**
 * Study Planner backend - JSON-file task store + AI timetable generation.
 */
public final class PlannerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlannerService() {
    }

    public static ArrayNode tasks(AuthService.AuthUser user) {
        JsonNode t = Store.read("planner_tasks.json").path("tasks");
        ArrayNode all = t.isArray() ? (ArrayNode) t : MAPPER.createArrayNode();
        ArrayNode mine = MAPPER.createArrayNode();
        boolean admin = "admin".equals(user.role());
        for (JsonNode task : all) {
            String owner = task.path("owner").asText("");
            if (admin || owner.isEmpty() || owner.equals(user.id())) {
                mine.add(task);
            }
        }
        return mine;
    }

    private static void save(ArrayNode tasks) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("tasks", tasks);
        Store.write("planner_tasks.json", root);
    }

    private static ArrayNode allTasks() {
        JsonNode t = Store.read("planner_tasks.json").path("tasks");
        return t.isArray() ? (ArrayNode) t : MAPPER.createArrayNode();
    }

    private static boolean canAccess(JsonNode task, AuthService.AuthUser user) {
        String owner = task.path("owner").asText("");
        return owner.isEmpty() || owner.equals(user.id()) || "admin".equals(user.role());
    }

    public static ObjectNode create(JsonNode req, AuthService.AuthUser user) {
        String title = req.path("title").asText("").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("title is required");

        ObjectNode t = MAPPER.createObjectNode();
        t.put("id", UUID.randomUUID().toString().substring(0, 8));
        t.put("title", title);
        t.put("subject", req.path("subject").asText("General"));
        t.put("due_date", req.path("due_date").asText(""));
        t.put("priority", req.path("priority").asText("medium"));
        t.put("description", req.path("description").asText(""));
        t.put("minutes", req.path("minutes").asInt(30));
        t.put("status", "pending");
        t.put("owner", user.id());
        t.put("created", java.time.Instant.now().toString());

        ArrayNode tasks = allTasks();
        tasks.add(t);
        save(tasks);
        return t;
    }

    public static ObjectNode update(String id, JsonNode patch, AuthService.AuthUser user) {
        ArrayNode tasks = allTasks();
        for (JsonNode t : tasks) {
            if (t.path("id").asText().equals(id)) {
                if (!canAccess(t, user)) throw new IllegalArgumentException("access denied");
                ObjectNode tn = (ObjectNode) t;
                for (String f : new String[]{"title", "subject", "due_date", "priority", "description", "status", "minutes"}) {
                    if (patch.has(f)) {
                        if (f.equals("minutes")) tn.put("minutes", patch.path(f).asInt());
                        else tn.put(f, patch.path(f).asText());
                    }
                }
                save(tasks);
                return tn;
            }
        }
        throw new IllegalArgumentException("task not found: " + id);
    }

    public static void delete(String id, AuthService.AuthUser user) {
        ArrayNode tasks = allTasks();
        ArrayNode kept = MAPPER.createArrayNode();
        boolean removed = false;
        for (JsonNode t : tasks) {
            if (t.path("id").asText().equals(id)) {
                if (!canAccess(t, user)) throw new IllegalArgumentException("access denied");
                removed = true;
            } else kept.add(t);
        }
        if (!removed) throw new IllegalArgumentException("task not found: " + id);
        save(kept);
    }

    /** AI timetable generation via OpenAI; honest error when no key. */
    public static String aiGenerate(JsonNode req) throws Exception {
        JsonNode prompts = JsonStore.load("data/openai_prompts.json");
        JsonNode t = prompts.path("planner");
        if (t.isMissingNode()) {
            throw new IllegalStateException("planner prompt not configured");
        }
        Map<String, String> vars = Map.of(
                "goal", req.path("goal").asText("Improve my grades"),
                "days", req.path("days").asText("7"),
                "minutes", req.path("minutes_per_day").asText("60"),
                "subjects", req.path("subjects").asText("General"));
        return AIProvider.generate(
                fill(t.path("instructions").asText(), vars),
                fill(t.path("input_template").asText(), vars));
    }

    private static String fill(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}
