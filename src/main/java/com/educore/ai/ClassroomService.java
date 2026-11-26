package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

/**
 * Classrooms backend - JSON store, single-user demo.
 * NOTE: no real authentication/roles yet. Join codes provide basic
 * access control only; teacher/admin RBAC is a planned feature.
 */
public final class ClassroomService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClassroomService() {
    }

    private static ObjectNode root() {
        return Store.obj(Store.read("classrooms.json"));
    }

    private static void save(ObjectNode root) {
        Store.write("classrooms.json", root);
    }

    public static ObjectNode create(JsonNode req, AuthService.AuthUser user) {
        String name = req.path("name").asText("").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("name is required");

        String code;
        do {
            code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        } while (root().has(code) || findByCode(code) != null);

        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", UUID.randomUUID().toString().substring(0, 8));
        c.put("name", name);
        c.put("code", code);
        c.put("teacher", req.path("teacher").asText(user.name()));
        c.put("owner_id", user.id());
        c.put("description", req.path("description").asText(""));
        c.put("created", java.time.Instant.now().toString());
        c.putArray("members");
        c.putArray("member_ids");
        c.putArray("announcements");
        c.putArray("materials");

        ObjectNode r = root();
        r.set(c.path("id").asText(), c);
        save(r);
        return c;
    }

    public static ObjectNode join(JsonNode req, AuthService.AuthUser user) {
        String code = req.path("code").asText("").trim().toUpperCase();
        String student = req.path("student").asText(user.name()).trim();
        JsonNode c = findByCode(code);
        if (c == null) throw new IllegalArgumentException("invalid join code");

        ObjectNode cn = (ObjectNode) c;
        ArrayNode members = (ArrayNode) cn.path("members");
        ArrayNode memberIds = cn.has("member_ids") ? (ArrayNode) cn.path("member_ids") : cn.putArray("member_ids");
        boolean exists = false;
        for (JsonNode m : memberIds) {
            if (user.id().equals(m.asText())) { exists = true; break; }
        }
        if (!exists) {
            members.add(student);
            memberIds.add(user.id());
            ObjectNode r = root();
            r.set(cn.path("id").asText(), cn);
            save(r);
        }
        return cn;
    }

    /** Owner, member, or admin may post announcements/materials. */
    public static boolean canParticipate(JsonNode classroom, AuthService.AuthUser user) {
        if ("admin".equals(user.role())) return true;
        if (classroom.path("owner_id").asText("").equals(user.id())) return true;
        for (JsonNode m : classroom.path("member_ids")) {
            if (m.asText().equals(user.id())) return true;
        }
        return false;
    }

    public static ArrayNode list() {
        ArrayNode arr = MAPPER.createArrayNode();
        JsonNode r = Store.read("classrooms.json");
        r.fieldNames().forEachRemaining(k -> arr.add(r.path(k)));
        return arr;
    }

    public static JsonNode get(String id) {
        JsonNode c = Store.read("classrooms.json").path(id);
        if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
        return c;
    }

    public static ObjectNode announce(String id, JsonNode req, AuthService.AuthUser user) {
        JsonNode c = get(id);
        if (!canParticipate(c, user)) throw new IllegalArgumentException("access denied");
        ObjectNode cn = (ObjectNode) c;
        ObjectNode a = ((ArrayNode) cn.path("announcements")).addObject();
        a.put("author", req.path("author").asText("Teacher"));
        a.put("text", req.path("text").asText(""));
        a.put("at", java.time.Instant.now().toString());
        ObjectNode r = root();
        r.set(id, cn);
        save(r);
        return a;
    }

    public static ObjectNode addMaterial(String id, JsonNode req, AuthService.AuthUser user) {
        JsonNode c = get(id);
        if (!canParticipate(c, user)) throw new IllegalArgumentException("access denied");
        ObjectNode cn = (ObjectNode) c;
        ObjectNode m = ((ArrayNode) cn.path("materials")).addObject();
        m.put("name", req.path("name").asText("material"));
        m.put("type", req.path("type").asText("link"));
        m.put("url", req.path("url").asText(""));
        m.put("added", java.time.Instant.now().toString());
        ObjectNode r = root();
        r.set(id, cn);
        save(r);
        return m;
    }

    private static JsonNode findByCode(String code) {
        JsonNode r = Store.read("classrooms.json");
        java.util.Iterator<Map.Entry<String, JsonNode>> it = r.fields();
        while (it.hasNext()) {
            JsonNode c = it.next().getValue();
            if (c.path("code").asText().equalsIgnoreCase(code)) return c;
        }
        return null;
    }
}
