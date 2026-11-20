package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Persistent per-user study-notes library backed by data/store/notes.json.
 * Every generated note is saved automatically; users can list, view, rename,
 * edit, download and delete only their own notes.
 */
public final class NotesService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONTENT_CHARS = 200_000;
    private static final int MAX_TITLE_CHARS = 200;

    private NotesService() {
    }

    private static ArrayNode items() {
        JsonNode f = Store.read("notes.json").path("items");
        return f.isArray() ? (ArrayNode) f : MAPPER.createArrayNode();
    }

    private static void save(ArrayNode items) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("items", items);
        Store.write("notes.json", root);
    }

    private static JsonNode find(String id) {
        for (JsonNode n : items()) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }

    private static void checkOwner(JsonNode note, AuthService.AuthUser user) {
        if (note == null) throw new IllegalArgumentException("note not found");
        String owner = note.path("owner").asText("");
        if (!owner.isEmpty() && !owner.equals(user.id()) && !"admin".equals(user.role())) {
            throw new IllegalArgumentException("access denied");
        }
    }

    /** Creates a saved note. Returns the stored node. */
    public static ObjectNode create(AuthService.AuthUser user, JsonNode req) {
        String content = req.path("content").asText("").trim();
        if (content.isEmpty()) throw new IllegalArgumentException("content is required");
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS);
        }
        String topic = trunc(req.path("topic").asText(""), 300);
        String title = trunc(req.path("title").asText(""), MAX_TITLE_CHARS);
        if (title.isEmpty()) title = topic.isEmpty() ? "Untitled note" : topic;

        ObjectNode note = MAPPER.createObjectNode();
        String id = UUID.randomUUID().toString().substring(0, 8);
        String now = Instant.now().toString();
        note.put("id", id);
        note.put("title", title);
        note.put("subject", trunc(req.path("subject").asText(""), 200));
        note.put("topic", topic);
        note.put("language", req.path("language").asText("English"));
        note.put("level", req.path("level").asText(""));
        note.put("content", content);
        note.put("owner", user.id());
        note.put("created", now);
        note.put("updated", now);

        ArrayNode all = items();
        all.add(note);
        save(all);
        return note;
    }

    /** Lists the user's notes (metadata only, newest first). Optional ?q= filter. */
    public static ArrayNode list(AuthService.AuthUser user, String query, String sort) {
        List<JsonNode> mine = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean admin = "admin".equals(user.role());
        for (JsonNode n : items()) {
            String owner = n.path("owner").asText("");
            if (!admin && !owner.isEmpty() && !owner.equals(user.id())) continue;
            if (!q.isEmpty()) {
                String hay = (n.path("title").asText() + " " + n.path("subject").asText()
                        + " " + n.path("topic").asText()).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            mine.add(n);
        }
        Comparator<JsonNode> cmp = "oldest".equals(sort)
                ? Comparator.comparing(n -> n.path("created").asText())
                : Comparator.comparing((JsonNode n) -> n.path("updated").asText()).reversed();
        if ("title".equals(sort)) {
            cmp = Comparator.comparing(n -> n.path("title").asText().toLowerCase());
        }
        mine.sort(cmp);

        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode n : mine) {
            ObjectNode meta = out.addObject();
            meta.put("id", n.path("id").asText());
            meta.put("title", n.path("title").asText());
            meta.put("subject", n.path("subject").asText());
            meta.put("topic", n.path("topic").asText());
            meta.put("language", n.path("language").asText());
            meta.put("created", n.path("created").asText());
            meta.put("updated", n.path("updated").asText());
            meta.put("chars", n.path("content").asText().length());
        }
        return out;
    }

    /** Returns one full note (content included) after ownership check. */
    public static ObjectNode get(String id, AuthService.AuthUser user) {
        JsonNode n = find(id);
        checkOwner(n, user);
        return (ObjectNode) n.deepCopy();
    }

    /** Updates title/content/metadata; bumps `updated`. */
    public static ObjectNode update(String id, AuthService.AuthUser user, JsonNode req) {
        ArrayNode all = items();
        ObjectNode note = null;
        for (JsonNode x : all) {
            if (id.equals(x.path("id").asText()) && x instanceof ObjectNode o) {
                note = o;
                break;
            }
        }
        checkOwner(note == null ? null : (JsonNode) note, user);
        if (req.has("title")) {
            String t = trunc(req.path("title").asText(""), MAX_TITLE_CHARS);
            if (!t.isBlank()) note.put("title", t);
        }
        if (req.has("content")) {
            String c = req.path("content").asText("");
            if (c.isBlank()) throw new IllegalArgumentException("content cannot be empty");
            note.put("content", c.length() > MAX_CONTENT_CHARS ? c.substring(0, MAX_CONTENT_CHARS) : c);
        }
        for (String f : new String[]{"subject", "topic", "language", "level"}) {
            if (req.has(f)) note.put(f, trunc(req.path(f).asText(""), 300));
        }
        note.put("updated", Instant.now().toString());
        save(all);
        return note;
    }

    public static void delete(String id, AuthService.AuthUser user) {
        JsonNode n = find(id);
        checkOwner(n, user);
        ArrayNode all = items();
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode x : all) {
            if (!id.equals(x.path("id").asText())) kept.add(x);
        }
        save(kept);
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
