package com.educore.ai;

import com.educore.ai.db.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * User-facing notifications: platform-wide announcements plus notifications
 * targeted at the individual user. Read-only for users; creation is admin-only
 * (AdminService.createNotification).
 */
public final class NotificationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotificationService() {
    }

    public static ArrayNode forUser(AuthService.AuthUser user, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        limit = Math.min(100, Math.max(1, limit));
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id, audience, title, body, created_at FROM notifications "
                                 + "WHERE audience = 'platform' OR user_id = ? "
                                 + "ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, user.id());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode n = out.addObject();
                        n.put("id", rs.getLong(1));
                        n.put("audience", rs.getString(2));
                        n.put("title", rs.getString(3));
                        n.put("body", rs.getString(4));
                        try { n.put("created_at", rs.getTimestamp(5).toInstant().toString()); }
                        catch (Exception e) { n.put("created_at", ""); }
                    }
                }
            } catch (Exception e) {
                System.err.println("[notif] list failed: " + e.getMessage());
            }
            return out;
        }
        // JSON mode
        {
            com.fasterxml.jackson.databind.JsonNode items = Store.read("notifications.json").path("items");
            if (items.isArray()) {
                for (int i = items.size() - 1; i >= 0 && out.size() < limit; i--) {
                    com.fasterxml.jackson.databind.JsonNode n = items.get(i);
                    String aud = n.path("audience").asText("platform");
                    String uid = n.path("user_id").asText("");
                    if ("platform".equals(aud) || uid.equals(user.id()) || uid.isEmpty()) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }
}
