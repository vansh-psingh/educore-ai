package com.educore.ai;

import com.educore.ai.db.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Admin dashboard backend. Every method assumes the caller is already
 * verified as role=admin by WebServer - defence in depth is still applied
 * here for role/status mutations.
 */
public final class AdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdminService() {
    }

    private static void requireAdmin(AuthService.AuthUser actor) {
        if (!"admin".equals(actor.role())) {
            throw new IllegalArgumentException("admin role required");
        }
    }

    // ================= overview =================

    public static ObjectNode overview(AuthService.AuthUser actor) {
        requireAdmin(actor);
        ObjectNode out = MAPPER.createObjectNode();
        if (Db.configured()) {
            try (Connection c = Db.conn()) {
                out.put("storage", "postgresql");
                out.put("db_ping", Db.ping());
                out.set("users_by_role", countBy(c,
                        "SELECT role, count(*) FROM app_users GROUP BY role"));
                out.set("users_by_status", countBy(c,
                        "SELECT status, count(*) FROM app_users GROUP BY status"));
                out.set("content_counts", contentCounts(c));
                out.set("recent_logins", recentLogins(c, 10));
            } catch (Exception e) {
                out.put("db_error", e.getMessage());
            }
        } else {
            out.put("storage", "json-files");
            out.put("db_ping", false);
            // JSON-mode counts
            ObjectNode roles = out.putObject("users_by_role");
            ObjectNode statuses = out.putObject("users_by_status");
            JsonNode users = Store.read("users.json");
            users.fields().forEachRemaining(e -> {
                JsonNode u = e.getValue();
                String role = u.path("role").asText("student");
                String status = u.path("status").asText("active");
                roles.put(role, roles.path(role).asInt(0) + 1);
                statuses.put(status, statuses.path(status).asInt(0) + 1);
            });
        }
        out.put("uptuptime_sec", Db.uptimeMs() / 1000);
        out.put("ai_provider", AIProvider.providerName());
        out.put("ai_cache_entries", AIProvider.cacheSize());
        out.put("smtp_configured", SmtpMailer.configured());
        return out;
    }

    private static ObjectNode countBy(Connection c, String sql) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) n.put(rs.getString(1), rs.getInt(2));
        }
        return n;
    }

    private static ObjectNode contentCounts(Connection c) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        String[][] pairs = {
                {"notes", "SELECT count(*) FROM notes"},
                {"feedback", "SELECT count(*) FROM feedback_items"},
                {"exams", "SELECT count(*) FROM exams"},
                {"exam_attempts", "SELECT count(*) FROM exam_attempts"},
                {"documents", "SELECT count(*) FROM documents"},
                {"planner_tasks", "SELECT count(*) FROM planner_tasks"},
                {"classrooms", "SELECT count(*) FROM classrooms"},
                {"chat_messages", "SELECT count(*) FROM chat_messages"},
                {"essays", "SELECT count(*) FROM essay_submissions"},
                {"audit_events", "SELECT count(*) FROM audit_log"},
        };
        for (String[] p : pairs) {
            try (PreparedStatement ps = c.prepareStatement(p[1]);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) n.put(p[0], rs.getInt(1));
            } catch (Exception ignored) {
                n.put(p[0], -1);
            }
        }
        return n;
    }

    private static ArrayNode recentLogins(Connection c, int limit) throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, email, role, last_login_at FROM app_users "
                        + "WHERE last_login_at IS NOT NULL ORDER BY last_login_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = arr.addObject();
                    n.put("id", rs.getString(1));
                    n.put("name", rs.getString(2));
                    n.put("email", rs.getString(3));
                    n.put("role", rs.getString(4));
                    n.put("last_login_at", rs.getTimestamp(5).toInstant().toString());
                }
            }
        }
        return arr;
    }

    // ================= users =================

    public static ObjectNode listUsers(AuthService.AuthUser actor, String q,
                                       String role, String status, int page, int size) {
        requireAdmin(actor);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode users = out.putArray("users");
        int total = 0;

        if (Db.configured()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, email, name, role, status, created_at, last_login_at, "
                            + "(SELECT count(*) FROM notes n WHERE n.owner_id = u.id) AS notes, "
                            + "(SELECT count(*) FROM exams e WHERE e.owner_id = u.id) AS exams "
                            + "FROM app_users u WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                sql.append(" AND (lower(email) LIKE ? OR lower(name) LIKE ?)");
                String like = "%" + q.trim().toLowerCase() + "%";
                params.add(like); params.add(like);
            }
            if (role != null && !role.isBlank()) { sql.append(" AND role = ?"); params.add(role); }
            if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); params.add(status); }
            try (Connection c = Db.conn()) {
                String where = sql.substring(sql.indexOf("WHERE 1=1"));
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM app_users u " + where)) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getInt(1); }
                }
                sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
                params.add(size);
                params.add((page - 1) * size);
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) users.add(userRow(rs));
                    }
                }
            } catch (Exception e) {
                out.put("error", e.getMessage());
            }
        } else {
            // JSON mode
            List<JsonNode> all = new ArrayList<>();
            JsonNode usersNode = Store.read("users.json");
            usersNode.fields().forEachRemaining(e -> all.add(e.getValue()));
            String ql = q == null ? "" : q.trim().toLowerCase();
            for (JsonNode u : all) {
                if (!ql.isEmpty()
                        && !(u.path("email").asText().toLowerCase().contains(ql)
                             || u.path("name").asText().toLowerCase().contains(ql))) continue;
                if (role != null && !role.isBlank() && !u.path("role").asText().equals(role)) continue;
                if (status != null && !status.isBlank() && !u.path("status").asText("active").equals(status)) continue;
                ObjectNode n = users.addObject();
                n.put("id", u.path("id").asText());
                n.put("email", u.path("email").asText());
                n.put("name", u.path("name").asText());
                n.put("role", u.path("role").asText("student"));
                n.put("status", u.path("status").asText("active"));
                n.put("created", u.path("created").asText(""));
                n.put("last_login_at", "");
                n.put("notes", 0);
                n.put("exams", 0);
                total++;
            }
        }
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    private static ObjectNode userRow(ResultSet rs) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("email", rs.getString("email"));
        n.put("name", rs.getString("name"));
        n.put("role", rs.getString("role"));
        n.put("status", rs.getString("status"));
        try { n.put("created", rs.getTimestamp("created_at").toInstant().toString()); }
        catch (Exception e) { n.put("created", ""); }
        try {
            java.sql.Timestamp t = rs.getTimestamp("last_login_at");
            n.put("last_login_at", t == null ? "" : t.toInstant().toString());
        } catch (Exception e) { n.put("last_login_at", ""); }
        n.put("notes", rs.getInt("notes"));
        n.put("exams", rs.getInt("exams"));
        return n;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws Exception {
        for (int i = 0; i < params.size(); i++) ps.setString(i + 1, (String) params.get(i));
    }

    public static ObjectNode getUser(AuthService.AuthUser actor, String userId) {
        requireAdmin(actor);
        ObjectNode out = MAPPER.createObjectNode();
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id, email, name, role, status, created_at, updated_at, last_login_at "
                                 + "FROM app_users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("user not found");
                    out.put("id", rs.getString(1));
                    out.put("email", rs.getString(2));
                    out.put("name", rs.getString(3));
                    out.put("role", rs.getString(4));
                    out.put("status", rs.getString(5));
                    try { out.put("created", rs.getTimestamp(6).toInstant().toString()); } catch (Exception e) { out.put("created", ""); }
                    try { out.put("updated", rs.getTimestamp(7).toInstant().toString()); } catch (Exception e) { out.put("updated", ""); }
                    try {
                        java.sql.Timestamp t = rs.getTimestamp(8);
                        out.put("last_login_at", t == null ? "" : t.toInstant().toString());
                    } catch (Exception e) { out.put("last_login_at", ""); }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("user lookup failed: " + e.getMessage());
            }
        } else {
            JsonNode users = Store.read("users.json");
            boolean[] found = {false};
            users.fields().forEachRemaining(e -> {
                JsonNode u = e.getValue();
                if (u.path("id").asText().equals(userId)) {
                    found[0] = true;
                    out.put("id", u.path("id").asText());
                    out.put("email", u.path("email").asText());
                    out.put("name", u.path("name").asText());
                    out.put("role", u.path("role").asText("student"));
                    out.put("status", u.path("status").asText("active"));
                    out.put("created", u.path("created").asText(""));
                    out.put("updated", u.path("updated").asText(""));
                    out.put("last_login_at", "");
                }
            });
            if (!found[0]) throw new IllegalArgumentException("user not found");
        }
        // activity counts
        if (Db.configured()) {
            try (Connection c = Db.conn()) {
                String[] tables = {"notes", "exams", "documents", "planner_tasks", "feedback_items", "chat_messages", "essay_submissions"};
                ObjectNode counts = out.putObject("activity");
                for (String t : tables) {
                    String col = t.equals("feedback_items") ? "user_id" : t.equals("chat_messages") ? "user_id"
                            : t.equals("essay_submissions") ? "user_id" : "owner_id";
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT count(*) FROM " + t + " WHERE " + col + " = ?")) {
                        ps.setString(1, userId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) counts.put(t, rs.getInt(1));
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Change a user's role or status. Admins cannot demote themselves. */
    public static ObjectNode updateUser(AuthService.AuthUser actor, String userId, JsonNode req) {
        requireAdmin(actor);
        String role = req.path("role").asText("");
        String status = req.path("status").asText("");
        if (role.isEmpty() && status.isEmpty()) {
            throw new IllegalArgumentException("nothing to update");
        }
        if (!role.isEmpty() && !List.of("student", "teacher", "admin").contains(role)) {
            throw new IllegalArgumentException("invalid role");
        }
        if (!status.isEmpty() && !List.of("active", "suspended", "deactivated").contains(status)) {
            throw new IllegalArgumentException("invalid status");
        }
        if (userId.equals(actor.id()) && !role.isEmpty() && !"admin".equals(role)) {
            throw new IllegalArgumentException("you cannot remove your own admin role");
        }

        if (Db.configured()) {
            StringBuilder sql = new StringBuilder("UPDATE app_users SET updated_at = now()");
            List<Object> params = new ArrayList<>();
            if (!role.isEmpty()) { sql.append(", role = ?"); params.add(role); }
            if (!status.isEmpty()) { sql.append(", status = ?"); params.add(status); }
            sql.append(" WHERE id = ?");
            params.add(userId);
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setString(i + 1, (String) params.get(i));
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("user not found");
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("user update failed: " + e.getMessage());
            }
        } else {
            // JSON mode: update the user node and rewrite the store
            ObjectNode root = Store.obj(Store.read("users.json"));
            boolean[] found = {false};
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode u = e.getValue();
                if (u.path("id").asText().equals(userId) && u instanceof ObjectNode un) {
                    found[0] = true;
                    if (!role.isEmpty()) un.put("role", role);
                    if (!status.isEmpty()) un.put("status", status);
                    un.put("updated", java.time.Instant.now().toString());
                    break;
                }
            }
            if (!found[0]) throw new IllegalArgumentException("user not found");
            Store.write("users.json", root);
        }
        AuditService.log(actor.id(), actor.email(), "admin_user_updated", userId, req);
        return getUser(actor, userId);
    }

    // ================= audit / health / notifications =================

    public static ArrayNode audit(AuthService.AuthUser actor, int limit) {
        requireAdmin(actor);
        return AuditService.recent(limit);
    }

    public static ObjectNode health(AuthService.AuthUser actor) {
        requireAdmin(actor);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("storage", Db.mode());
        out.put("db_configured", Db.configured());
        out.put("db_ping", Db.ping());
        out.put("db_init_error", Db.initError());
        out.put("uptime_sec", Db.uptimeMs() / 1000);
        out.put("ai_provider", AIProvider.providerName());
        out.put("ai_cache_entries", AIProvider.cacheSize());
        out.put("smtp_configured", SmtpMailer.configured());
        out.put("memory_mb", Runtime.getRuntime().totalMemory() / (1024 * 1024));
        out.put("processors", Runtime.getRuntime().availableProcessors());
        return out;
    }

    // ---- notifications ----

    public static ObjectNode createNotification(AuthService.AuthUser actor, JsonNode req) {
        requireAdmin(actor);
        String title = req.path("title").asText("").trim();
        String body = req.path("body").asText("").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("title is required");
        String audience = req.path("audience").asText("platform");
        if (!List.of("platform", "user").contains(audience)) {
            throw new IllegalArgumentException("audience must be platform or user");
        }
        String targetUser = req.path("user_id").asText("");
        if ("user".equals(audience) && targetUser.isEmpty()) {
            throw new IllegalArgumentException("user_id is required for user notifications");
        }

        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO notifications(user_id, audience, title, body, created_by) "
                                 + "VALUES (?,?,?,?,?) RETURNING id, created_at")) {
                ps.setString(1, "user".equals(audience) ? targetUser : null);
                ps.setString(2, audience);
                ps.setString(3, title);
                ps.setString(4, body);
                ps.setString(5, actor.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ObjectNode out = MAPPER.createObjectNode();
                    out.put("id", rs.getLong(1));
                    out.put("title", title);
                    out.put("body", body);
                    out.put("audience", audience);
                    out.put("created_at", rs.getTimestamp(2).toInstant().toString());
                    AuditService.log(actor.id(), actor.email(), "admin_notification_created",
                            String.valueOf(rs.getLong(1)));
                    return out;
                }
            } catch (Exception e) {
                throw new IllegalStateException("notification create failed: " + e.getMessage());
            }
        }
        // JSON mode: append to notifications.json
        ObjectNode root = Store.obj(Store.read("notifications.json"));
        ArrayNode items = root.path("items").isArray()
                ? (ArrayNode) root.path("items") : root.putArray("items");
        ObjectNode n = items.addObject();
        n.put("id", "n" + System.currentTimeMillis());
        n.put("user_id", targetUser);
        n.put("audience", audience);
        n.put("title", title);
        n.put("body", body);
        n.put("created_by", actor.id());
        n.put("created_at", java.time.Instant.now().toString());
        Store.write("notifications.json", root);
        AuditService.log(actor.id(), actor.email(), "admin_notification_created", n.path("id").asText());
        return n;
    }

    public static ArrayNode listNotifications(AuthService.AuthUser actor) {
        requireAdmin(actor);
        ArrayNode out = MAPPER.createArrayNode();
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id, user_id, audience, title, body, created_by, created_at "
                                 + "FROM notifications ORDER BY id DESC LIMIT 100")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode n = out.addObject();
                        n.put("id", rs.getLong(1));
                        String uid = rs.getString(2);
                        n.put("user_id", uid == null ? "" : uid);
                        n.put("audience", rs.getString(3));
                        n.put("title", rs.getString(4));
                        n.put("body", rs.getString(5));
                        n.put("created_by", rs.getString(6));
                        try { n.put("created_at", rs.getTimestamp(7).toInstant().toString()); }
                        catch (Exception e) { n.put("created_at", ""); }
                    }
                }
            } catch (Exception e) {
                System.err.println("[admin] notifications list failed: " + e.getMessage());
            }
            return out;
        }
        JsonNode items = Store.read("notifications.json").path("items");
        if (items.isArray()) {
            for (int i = items.size() - 1; i >= 0 && out.size() < 100; i--) {
                out.add(items.get(i));
            }
        }
        return out;
    }
}
