package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classrooms backend - persistent multi-user classrooms.
 *
 * Data model (classrooms.json, keyed by classroom id):
 *   id, name, code (6-char join code), teacher, owner_id, description,
 *   created, members[] ({id,name,role,joined} - legacy entries may be
 *   plain name strings), member_ids[] (parallel id list),
 *   announcements[], materials[]
 *
 * Roles: "owner" (creator), "student" (joined via code), "admin" (site admin).
 * All mutations are atomic read-modify-write under CLASS_LOCK so
 * concurrent joins/leaves cannot lose updates.
 */
public final class ClassroomService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object CLASS_LOCK = new Object();

    private ClassroomService() {
    }

    private static ObjectNode root() {
        return Store.obj(Store.read("classrooms.json"));
    }

    private static void save(ObjectNode root) {
        Store.write("classrooms.json", root);
    }

    // ================= membership helpers =================

    /** Ids of everyone who participates (owner + joined members). */
    private static boolean isParticipant(JsonNode c, AuthService.AuthUser user) {
        if ("admin".equals(user.role())) return true;
        if (c.path("owner_id").asText("").equals(user.id())) return true;
        for (JsonNode m : c.path("member_ids")) {
            if (m.asText().equals(user.id())) return true;
        }
        return false;
    }

    private static boolean isOwner(JsonNode c, AuthService.AuthUser user) {
        return "admin".equals(user.role())
                || c.path("owner_id").asText("").equals(user.id());
    }

    /** Role string for the response payload: owner / student / admin. */
    private static String myRole(JsonNode c, AuthService.AuthUser user) {
        if ("admin".equals(user.role()) && !c.path("owner_id").asText("").equals(user.id()))
            return "admin";
        if (c.path("owner_id").asText("").equals(user.id())) return "owner";
        return "student";
    }

    private static JsonNode findByCode(String code) {
        JsonNode r = Store.read("classrooms.json");
        Iterator<Map.Entry<String, JsonNode>> it = r.fields();
        while (it.hasNext()) {
            JsonNode c = it.next().getValue();
            if (c.path("code").asText().equalsIgnoreCase(code)) return c;
        }
        return null;
    }

    /** Adds {id,name,role,joined} to members[] and id to member_ids[]. */
    private static void addMember(ObjectNode c, AuthService.AuthUser user, String role) {
        ArrayNode members = c.has("members") && c.path("members").isArray()
                ? (ArrayNode) c.path("members") : c.putArray("members");
        ObjectNode m = members.addObject();
        m.put("id", user.id());
        m.put("name", user.name());
        m.put("role", role);
        m.put("joined", Instant.now().toString());

        ArrayNode ids = c.has("member_ids") && c.path("member_ids").isArray()
                ? (ArrayNode) c.path("member_ids") : c.putArray("member_ids");
        ids.add(user.id());
    }

    /** Removes a member by user id from both members[] and member_ids[]. */
    private static void removeMemberById(ObjectNode c, String userId) {
        ArrayNode ids = (ArrayNode) c.path("member_ids");
        ArrayNode keptIds = MAPPER.createArrayNode();
        for (JsonNode m : ids) if (!m.asText().equals(userId)) keptIds.add(m.asText());
        ((ObjectNode) c).set("member_ids", keptIds);

        ArrayNode members = c.path("members").isArray() ? (ArrayNode) c.path("members") : MAPPER.createArrayNode();
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode m : members) {
            // entries may be legacy plain strings or {id,...} objects
            String mid = m.isObject() ? m.path("id").asText("") : "";
            if (!mid.equals(userId)) kept.add(m);
        }
        ((ObjectNode) c).set("members", kept);
    }

    private static int memberCount(JsonNode c) {
        return c.path("member_ids").isArray() ? c.path("member_ids").size() : 0;
    }

    // ================= public API =================

    /** Any authenticated user may create; creator becomes owner. */
    public static ObjectNode create(JsonNode req, AuthService.AuthUser user) {
        String name = req.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 80)
            throw new IllegalArgumentException("Classroom name is required (max 80 chars).");
        String desc = req.path("description").asText("").trim();
        if (desc.length() > 300)
            throw new IllegalArgumentException("Description is too long (max 300 chars).");

        synchronized (CLASS_LOCK) {
            String code;
            do {
                code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            } while (findByCode(code) != null);

            ObjectNode c = MAPPER.createObjectNode();
            c.put("id", UUID.randomUUID().toString().substring(0, 8));
            c.put("name", name);
            c.put("code", code);
            c.put("teacher", user.name());
            c.put("owner_id", user.id());
            c.put("description", desc);
            c.put("created", Instant.now().toString());
            c.putArray("members");
            c.putArray("member_ids");
            c.putArray("announcements");
            c.putArray("materials");
            addMember(c, user, "owner"); // owner counts as a member

            ObjectNode r = root();
            r.set(c.path("id").asText(), c);
            save(r);
            return withRole(c, user);
        }
    }

    /** Join via 6-char code. Idempotent - rejoining returns the classroom. */
    public static ObjectNode join(JsonNode req, AuthService.AuthUser user) {
        String code = req.path("code").asText("").trim().toUpperCase();
        if (code.isEmpty()) throw new IllegalArgumentException("Enter a join code.");

        synchronized (CLASS_LOCK) {
            JsonNode found = findByCode(code);
            if (found == null || !found.isObject())
                throw new IllegalArgumentException("Invalid join code - check and try again.");
            ObjectNode c = (ObjectNode) found.deepCopy();

            // already a member? return current state (idempotent join)
            for (JsonNode m : c.path("member_ids")) {
                if (m.asText().equals(user.id())) return withRole(c, user);
            }
            addMember(c, user, "student");

            ObjectNode r = root();
            r.set(c.path("id").asText(), c);
            save(r);
            return withRole(c, user);
        }
    }

    /** Classrooms the user owns or has joined (admins see all). */
    public static ArrayNode list(AuthService.AuthUser user) {
        ArrayNode arr = MAPPER.createArrayNode();
        JsonNode r = Store.read("classrooms.json");
        Iterator<Map.Entry<String, JsonNode>> it = r.fields();
        while (it.hasNext()) {
            JsonNode c = it.next().getValue();
            if (!"admin".equals(user.role()) && !isParticipant(c, user)) continue;
            arr.add(withRole((ObjectNode) c.deepCopy(), user));
        }
        return arr;
    }

    /** Classroom detail - participants and admins only. */
    public static ObjectNode get(String id, AuthService.AuthUser user) {
        JsonNode c = Store.read("classrooms.json").path(id);
        if (c.isMissingNode() || !c.isObject())
            throw new IllegalArgumentException("classroom not found");
        if (!isParticipant(c, user))
            throw new IllegalArgumentException("access denied - join with the class code first");
        return withRole((ObjectNode) c.deepCopy(), user);
    }

    /** Student leaves; owners cannot leave (they delete instead). */
    public static ObjectNode leave(String id, AuthService.AuthUser user) {
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            ObjectNode cn = (ObjectNode) c.deepCopy();
            if (isOwner(cn, user))
                throw new IllegalArgumentException("Owners cannot leave - delete the classroom instead.");
            if (!isParticipant(cn, user))
                throw new IllegalArgumentException("access denied");
            removeMemberById(cn, user.id());
            ObjectNode r = root();
            r.set(id, cn);
            save(r);
            return withRole(cn, user);
        }
    }

    /** Owner/admin removes a member by user id. */
    public static ObjectNode removeMember(String id, JsonNode req, AuthService.AuthUser user) {
        String memberId = req.path("member_id").asText("").trim();
        if (memberId.isEmpty()) throw new IllegalArgumentException("member_id is required");
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            ObjectNode cn = (ObjectNode) c.deepCopy();
            if (!isOwner(cn, user)) throw new IllegalArgumentException("only the owner can remove members");
            if (cn.path("owner_id").asText("").equals(memberId))
                throw new IllegalArgumentException("cannot remove the classroom owner");
            boolean wasMember = false;
            for (JsonNode m : cn.path("member_ids")) {
                if (m.asText().equals(memberId)) { wasMember = true; break; }
            }
            if (!wasMember) throw new IllegalArgumentException("member not found in this classroom");
            removeMemberById(cn, memberId);
            ObjectNode r = root();
            r.set(id, cn);
            save(r);
            return withRole(cn, user);
        }
    }

    /** Owner/admin edits classroom info (name, description). */
    public static ObjectNode update(String id, JsonNode req, AuthService.AuthUser user) {
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            ObjectNode cn = (ObjectNode) c.deepCopy();
            if (!isOwner(cn, user)) throw new IllegalArgumentException("only the owner can edit this classroom");
            if (req.has("name")) {
                String name = req.path("name").asText("").trim();
                if (name.isEmpty() || name.length() > 80)
                    throw new IllegalArgumentException("Classroom name is required (max 80 chars).");
                cn.put("name", name);
            }
            if (req.has("description")) {
                String desc = req.path("description").asText("").trim();
                if (desc.length() > 300)
                    throw new IllegalArgumentException("Description is too long (max 300 chars).");
                cn.put("description", desc);
            }
            ObjectNode r = root();
            r.set(id, cn);
            save(r);
            return withRole(cn, user);
        }
    }

    /** Owner/admin deletes the classroom and all its data. */
    public static void delete(String id, AuthService.AuthUser user) {
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            if (!isOwner(c, user)) throw new IllegalArgumentException("only the owner can delete this classroom");
            ObjectNode r = root();
            r.remove(id);
            save(r);
        }
    }

    /** Participants (or admin) may post announcements; author = real user. */
    public static ObjectNode announce(String id, JsonNode req, AuthService.AuthUser user) {
        String text = req.path("text").asText("").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("announcement text is required");
        if (text.length() > 1000) throw new IllegalArgumentException("announcement too long (max 1000 chars)");
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            if (!isParticipant(c, user)) throw new IllegalArgumentException("access denied");
            ObjectNode cn = (ObjectNode) c.deepCopy();
            ObjectNode a = ((ArrayNode) cn.path("announcements")).addObject();
            a.put("author", user.name());
            a.put("author_id", user.id());
            a.put("text", text);
            a.put("at", Instant.now().toString());
            ObjectNode r = root();
            r.set(id, cn);
            save(r);
            return a;
        }
    }

    /** Participants (or admin) may add shared-material links. */
    public static ObjectNode addMaterial(String id, JsonNode req, AuthService.AuthUser user) {
        String name = req.path("name").asText("").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("material name is required");
        synchronized (CLASS_LOCK) {
            JsonNode c = Store.read("classrooms.json").path(id);
            if (c.isMissingNode()) throw new IllegalArgumentException("classroom not found");
            if (!isParticipant(c, user)) throw new IllegalArgumentException("access denied");
            ObjectNode cn = (ObjectNode) c.deepCopy();
            ObjectNode m = ((ArrayNode) cn.path("materials")).addObject();
            m.put("name", name);
            m.put("type", req.path("type").asText("link"));
            m.put("url", req.path("url").asText(""));
            m.put("added_by", user.id());
            m.put("added", Instant.now().toString());
            ObjectNode r = root();
            r.set(id, cn);
            save(r);
            return m;
        }
    }

    /** Attaches my_role + normalized member count for the response. */
    private static ObjectNode withRole(ObjectNode c, AuthService.AuthUser user) {
        ObjectNode out = (ObjectNode) c.deepCopy();
        out.put("my_role", myRole(c, user));
        out.put("member_count", memberCount(c));
        return out;
    }

    // kept for any legacy callers
    public static boolean canParticipate(JsonNode classroom, AuthService.AuthUser user) {
        return isParticipant(classroom, user);
    }
}
