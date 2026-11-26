package com.educore.ai;

import com.educore.ai.db.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit trail for security/administrative events.
 * PostgreSQL-backed (audit_log table) when configured; otherwise a bounded
 * in-memory ring buffer so dev mode still records events.
 */
public final class AuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MEM_MAX = 500;
    private static final Deque<ObjectNode> MEM = new ArrayDeque<>();

    private AuditService() {
    }

    public static void log(String actorId, String actorEmail, String action,
                           String target, JsonNode detail) {
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO audit_log(actor_id, actor_email, action, target, detail) "
                                 + "VALUES (?,?,?,?,?::jsonb)")) {
                ps.setString(1, actorId);
                ps.setString(2, actorEmail);
                ps.setString(3, action);
                ps.setString(4, target);
                ps.setString(5, detail == null ? "{}" : MAPPER.writeValueAsString(detail));
                ps.executeUpdate();
                return;
            } catch (Exception e) {
                System.err.println("[audit] db write failed: " + e.getMessage());
            }
        }
        synchronized (MEM) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("actor_id", actorId == null ? "" : actorId);
            n.put("actor_email", actorEmail == null ? "" : actorEmail);
            n.put("action", action);
            n.put("target", target == null ? "" : target);
            n.set("detail", detail == null ? MAPPER.createObjectNode() : detail);
            n.put("created_at", java.time.Instant.now().toString());
            MEM.addLast(n);
            while (MEM.size() > MEM_MAX) MEM.pollFirst();
        }
    }

    public static void log(String actorId, String actorEmail, String action, String target) {
        log(actorId, actorEmail, action, target, null);
    }

    /** Recent audit entries, newest first. */
    public static ArrayNode recent(int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT actor_id, actor_email, action, target, detail, created_at "
                                 + "FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, Math.min(500, Math.max(1, limit)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode n = out.addObject();
                        n.put("actor_id", rs.getString(1));
                        n.put("actor_email", rs.getString(2));
                        n.put("action", rs.getString(3));
                        n.put("target", rs.getString(4));
                        try {
                            n.set("detail", MAPPER.readTree(rs.getString(5)));
                        } catch (Exception e) {
                            n.putObject("detail");
                        }
                        n.put("created_at", rs.getTimestamp(6).toInstant().toString());
                    }
                }
                return out;
            } catch (Exception e) {
                System.err.println("[audit] db read failed: " + e.getMessage());
            }
        }
        synchronized (MEM) {
            List<ObjectNode> copy = new ArrayList<>(MEM);
            for (int i = copy.size() - 1; i >= 0 && out.size() < limit; i--) {
                out.add(copy.get(i));
            }
        }
        return out;
    }
}
