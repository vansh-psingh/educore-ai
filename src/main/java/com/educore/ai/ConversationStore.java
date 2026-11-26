package com.educore.ai;

import com.educore.ai.db.Db;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session conversation history for the universal AI chat.
 *
 * PostgreSQL mode: every turn is persisted in chat_messages keyed by
 * session_id (format "userId:chatKey") so history survives restarts and is
 * isolated per user. Memory mode (no DATABASE_URL): bounded in-memory deque,
 * same semantics as before.
 *
 * Context sent to the model is capped at MAX_TURNS; the full history is kept
 * for restore/history-management endpoints.
 */
public final class ConversationStore {

    public record Turn(String role, String text) {} // role: "user" | "model"

    private static final int MAX_TURNS = 16;   // ~8 exchanges sent as context
    private static final int KEEP_ROWS = 400;   // prune beyond this per session

    private static final Map<String, Deque<Turn>> SESSIONS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConversationStore() {
    }

    /** userId embedded in "userId:sessionKey" session ids. */
    private static String userOf(String sessionId) {
        if (sessionId == null) return null;
        int i = sessionId.indexOf(':');
        return i > 0 ? sessionId.substring(0, i) : null;
    }

    /** Recent turns used as model context (newest MAX_TURNS, oldest-first). */
    public static List<Turn> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        if (Db.configured()) {
            List<Turn> all = dbTurns(sessionId, MAX_TURNS);
            return all;
        }
        Deque<Turn> d = SESSIONS.get(sessionId);
        if (d == null) return List.of();
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    /** Full persisted history for restore UI (oldest-first, capped at 400). */
    public static List<Turn> allHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        if (Db.configured()) {
            return dbTurns(sessionId, KEEP_ROWS);
        }
        Deque<Turn> d = SESSIONS.get(sessionId);
        if (d == null) return List.of();
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    public static void add(String sessionId, String role, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null) return;
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO chat_messages(session_id, user_id, role, text) VALUES (?,?,?,?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, userOf(sessionId));
                ps.setString(3, "model".equals(role) ? "model" : "user");
                ps.setString(4, text);
                ps.executeUpdate();
                prune(c, sessionId);
            } catch (Exception e) {
                System.err.println("[chat] persist failed: " + e.getMessage());
            }
            return;
        }
        Deque<Turn> d = SESSIONS.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(new Turn(role, text));
            while (d.size() > MAX_TURNS) {
                d.pollFirst();
            }
        }
    }

    /** Deletes all messages for a session. */
    public static void clear(String sessionId) {
        if (sessionId == null) return;
        SESSIONS.remove(sessionId);
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM chat_messages WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            } catch (Exception e) {
                System.err.println("[chat] clear failed: " + e.getMessage());
            }
        }
    }

    /** Lists the user's chat sessions with a preview of the last message. */
    public static ArrayNode sessionsForUser(String userId) {
        ArrayNode out = MAPPER.createArrayNode();
        if (userId == null || userId.isBlank()) return out;
        if (Db.configured()) {
            // one row per session: latest message id + preview
            String sql = """
                    SELECT session_id,
                           (SELECT text FROM chat_messages m2
                             WHERE m2.session_id = m.session_id
                             ORDER BY m2.id DESC LIMIT 1) AS last_text,
                           (SELECT role FROM chat_messages m3
                             WHERE m3.session_id = m.session_id
                             ORDER BY m3.id DESC LIMIT 1) AS last_role,
                           MAX(created_at) AS last_at,
                           COUNT(*) AS turns
                    FROM chat_messages m
                    WHERE user_id = ?
                    GROUP BY session_id
                    ORDER BY last_at DESC
                    LIMIT 50
                    """;
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode n = out.addObject();
                        n.put("session_id", rs.getString(1));
                        n.put("preview", trunc(rs.getString(2), 80));
                        n.put("last_role", rs.getString(3));
                        n.put("turns", rs.getInt(5));
                        try {
                            n.put("last_at", rs.getTimestamp(4).toInstant().toString());
                        } catch (Exception e) {
                            n.put("last_at", "");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[chat] sessions query failed: " + e.getMessage());
            }
            return out;
        }
        // memory fallback: group in-memory sessions by prefix userId:
        SESSIONS.forEach((sid, dq) -> {
            if (sid.startsWith(userId + ":") && !dq.isEmpty()) {
                synchronized (dq) {
                    Turn last = dq.peekLast();
                    ObjectNode n = out.addObject();
                    n.put("session_id", sid);
                    n.put("preview", trunc(last.text(), 80));
                    n.put("last_role", last.role());
                    n.put("turns", dq.size());
                    n.put("last_at", "");
                }
            }
        });
        return out;
    }

    private static List<Turn> dbTurns(String sessionId, int limit) {
        // fetch newest `limit` rows then return oldest-first
        List<Turn> rev = new ArrayList<>();
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT role, text FROM chat_messages WHERE session_id = ? "
                             + "ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rev.add(new Turn(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (Exception e) {
            System.err.println("[chat] history read failed: " + e.getMessage());
        }
        List<Turn> out = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) out.add(rev.get(i));
        return out;
    }

    private static void prune(Connection c, String sessionId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM chat_messages WHERE session_id = ? AND id < ("
                        + "SELECT MIN(id) FROM (SELECT id FROM chat_messages "
                        + "WHERE session_id = ? ORDER BY id DESC LIMIT ?) t)")) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            ps.setInt(3, KEEP_ROWS);
            ps.executeUpdate();
        }
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
