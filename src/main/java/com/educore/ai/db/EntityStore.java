package com.educore.ai.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relational adapter behind Store. Each legacy JSON store file maps to one
 * typed table. Reads rebuild the original JSON shape; writes diff the incoming
 * state against the table and upsert/delete rows inside a transaction, so the
 * existing service code keeps working unchanged while data lives in PostgreSQL.
 *
 * Every row keeps the complete original node in `payload jsonb` plus typed
 * columns extracted via {@link Spec#columns} (db column -> json field name).
 */
public final class EntityStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Shape { ARRAY, MAP }

    /**
     * @param file       store file name, e.g. "notes.json"
     * @param table      target table
     * @param shape      ARRAY = {key:[...]}, MAP = {key:obj}
     * @param arrayKey   JSON key holding the array (ARRAY shape only)
     * @param keyField   JSON field used as the map key on rebuild (MAP shape only)
     * @param columns    db column -> json field name for typed columns
     * @param intColumns db columns that need int casting
     */
    public record Spec(String file, String table, Shape shape, String arrayKey,
                       String keyField, Map<String, String> columns,
                       Set<String> intColumns) {
        public Spec {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
            intColumns = intColumns == null ? Set.of() : Set.copyOf(intColumns);
        }
    }

    private EntityStore() {
    }

    // ================= registry =================

    private static Map<String, String> cols(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    public static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry("users.json", new Spec("users.json", "app_users", Shape.MAP, null, "email",
                    cols("id", "id", "email", "email", "name", "name", "role", "role",
                            "status", "status", "pass_hash", "pass_hash",
                            "created_at", "created", "updated_at", "updated",
                            "last_login_at", "last_login"),
                    Set.of())),
            Map.entry("notes.json", new Spec("notes.json", "notes", Shape.ARRAY, "items", null,
                    cols("id", "id", "owner_id", "owner", "title", "title", "subject", "subject",
                            "topic", "topic", "language", "language", "level", "level",
                            "content", "content", "created_at", "created", "updated_at", "updated"),
                    Set.of())),
            Map.entry("feedback.json", new Spec("feedback.json", "feedback_items", Shape.ARRAY, "items", null,
                    cols("id", "id", "user_id", "user", "rating", "rating", "question", "question",
                            "answer", "answer", "comment", "comment", "created_at", "created"),
                    Set.of("rating"))),
            Map.entry("planner_tasks.json", new Spec("planner_tasks.json", "planner_tasks", Shape.ARRAY, "tasks", null,
                    cols("id", "id", "owner_id", "owner", "title", "title", "subject", "subject",
                            "status", "status", "priority", "priority", "due_date", "due_date",
                            "created_at", "created"),
                    Set.of())),
            Map.entry("exams.json", new Spec("exams.json", "exams", Shape.MAP, null, "id",
                    cols("id", "id", "owner_id", "owner", "title", "title", "subject", "subject",
                            "topic", "topic", "level", "level", "difficulty", "difficulty",
                            "duration_min", "duration_min", "created_at", "created"),
                    Set.of("duration_min"))),
            Map.entry("exam_history.json", new Spec("exam_history.json", "exam_attempts", Shape.ARRAY, "attempts", null,
                    cols("id", "id", "exam_id", "exam_id", "user_id", "owner", "score_pct", "score",
                            "marks", "marks", "created_at", "date"),
                    Set.of("score_pct"))),
            Map.entry("classrooms.json", new Spec("classrooms.json", "classrooms", Shape.MAP, null, "id",
                    cols("id", "id", "code", "code", "owner_id", "owner_id", "name", "name",
                            "teacher", "teacher", "created_at", "created"),
                    Set.of())),
            Map.entry("documents.json", new Spec("documents.json", "documents", Shape.MAP, null, "id",
                    cols("id", "id", "owner_id", "owner", "name", "name", "ext", "ext",
                            "mime", "type", "size_bytes", "size", "text_length", "text_length",
                            "created_at", "uploaded"),
                    Set.of("size_bytes", "text_length")))
    );

    // ================= read =================

    /** Rebuilds the original JSON store document from relational rows. */
    public static JsonNode read(Spec spec) throws Exception {
        Map<String, JsonNode> rows = new HashMap<>(); // pk -> payload (unordered)
        List<JsonNode> ordered = new ArrayList<>();   // ARRAY shape order by seq
        String sql = "SELECT id, payload FROM " + spec.table() + " ORDER BY seq, id";
        try (Connection c = Db.conn();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JsonNode payload = MAPPER.readTree(rs.getString("payload"));
                rows.put(rs.getString("id"), payload);
                ordered.add(payload);
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        if (spec.shape() == Shape.ARRAY) {
            ArrayNode arr = out.putArray(spec.arrayKey());
            for (JsonNode n : ordered) arr.add(n);
        } else {
            String keyField = spec.keyField();
            for (JsonNode n : ordered) {
                String key = n.path(keyField).asText("");
                if (key.isEmpty()) key = rows.entrySet().stream()
                        .filter(e -> e.getValue() == n).map(Map.Entry::getKey).findFirst().orElse("");
                if (!key.isEmpty()) out.set(key, n);
            }
        }
        return out;
    }

    // ================= write (diff + upsert in one transaction) =================

    /**
     * Diffs {@code doc} (the full new store document) against the table:
     * inserts/updates changed rows, deletes rows that disappeared.
     */
    public static void write(Spec spec, JsonNode doc) throws Exception {
        // collect incoming entities: pk -> node
        Map<String, JsonNode> incoming = new HashMap<>();
        List<JsonNode> arrayOrder = new ArrayList<>();
        if (spec.shape() == Shape.ARRAY) {
            JsonNode arr = doc.path(spec.arrayKey());
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String id = n.path("id").asText("");
                    if (!id.isEmpty()) {
                        incoming.put(id, n);
                        arrayOrder.add(n);
                    }
                }
            }
        } else {
            Iterator<Map.Entry<String, JsonNode>> it = doc.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode n = e.getValue();
                String id = n.path("id").asText(e.getKey());
                if (!id.isEmpty()) incoming.put(id, n);
            }
        }

        try (Connection c = Db.conn()) {
            c.setAutoCommit(false);
            try {
                // current rows
                Map<String, JsonNode> current = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, payload FROM " + spec.table());
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        current.put(rs.getString("id"), MAPPER.readTree(rs.getString("payload")));
                    }
                }

                Set<String> seen = new HashSet<>();
                for (Map.Entry<String, JsonNode> e : incoming.entrySet()) {
                    String id = e.getKey();
                    JsonNode node = e.getValue();
                    seen.add(id);
                    JsonNode old = current.get(id);
                    if (old != null && old.equals(node)) continue; // unchanged
                    upsert(c, spec, id, node);
                }
                // delete rows that vanished
                List<String> stale = new ArrayList<>();
                for (String id : current.keySet()) {
                    if (!seen.contains(id)) stale.add(id);
                }
                if (!stale.isEmpty()) {
                    String in = String.join(",", stale.stream().map(s -> "'" + s.replace("'", "''") + "'").toList());
                    try (var st = c.createStatement()) {
                        st.executeUpdate("DELETE FROM " + spec.table() + " WHERE id IN (" + in + ")");
                    }
                }
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    private static void upsert(Connection c, Spec spec, String id, JsonNode node) throws Exception {
        // gather typed column values
        Map<String, Object> vals = new HashMap<>();
        vals.put("id", id);
        vals.put("payload", MAPPER.writeValueAsString(node));
        for (Map.Entry<String, String> col : spec.columns().entrySet()) {
            String dbCol = col.getKey();
            if (dbCol.equals("id") || dbCol.equals("payload")) continue;
            String field = col.getValue();
            JsonNode v = node.path(field);
            if (spec.intColumns().contains(dbCol)) {
                vals.put(dbCol, v.isNumber() ? v.asInt() : (v.asText("").isBlank() ? null : Integer.parseInt(v.asText())));
            } else {
                String s = v.isTextual() || v.isNumber() || v.isBoolean() ? v.asText("") : "";
                boolean missing = s.isEmpty() && !v.isTextual() && !v.isNumber() && !v.isBoolean();
                // FK columns: blank/missing -> NULL so rows without owners still insert
                if ((dbCol.equals("owner_id") || dbCol.equals("user_id") || dbCol.equals("exam_id"))
                        && s.isEmpty()) {
                    vals.put(dbCol, null);
                } else {
                    vals.put(dbCol, missing ? null : s);
                }
            }
        }

        // build column list = id + payload + spec columns present in vals
        List<String> cols = new ArrayList<>();
        cols.add("id");
        for (String k : spec.columns().keySet()) if (!k.equals("id")) cols.add(k);
        cols.add("payload");

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(spec.table()).append(" (");
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) { sql.append(','); ph.append(','); }
            sql.append(cols.get(i));
            String colName = cols.get(i);
            ph.append(colName.equals("payload") ? "?::jsonb"
                    : colName.equals("created_at") || colName.equals("updated_at")
                            ? "COALESCE(?::timestamptz, now())"
                    : colName.endsWith("_at") ? "?::timestamptz" : "?");
        }
        sql.append(") VALUES (").append(ph).append(") ON CONFLICT (id) DO UPDATE SET ");
        List<String> updates = new ArrayList<>();
        for (String col : cols) {
            if (col.equals("id")) continue;
            updates.add(col + " = EXCLUDED." + col);
        }
        sql.append(String.join(", ", updates));

        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < cols.size(); i++) {
                String col = cols.get(i);
                Object v = col.equals("id") ? id : col.equals("payload") ? vals.get("payload") : vals.get(col);
                if (v == null) {
                    // FK columns stay NULL; NOT NULL typed columns get type-appropriate
                    // empty defaults (payload jsonb remains the source of truth on read)
                    if (col.equals("owner_id") || col.equals("user_id") || col.equals("exam_id")) {
                        ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    } else if (spec.intColumns().contains(col)) {
                        if (col.equals("size_bytes")) ps.setLong(i + 1, 0);
                        else ps.setInt(i + 1, 0);
                    } else if (col.endsWith("_at")) {
                        ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(i + 1, "");
                    }
                } else if (v instanceof Integer iv) ps.setInt(i + 1, iv);
                else ps.setString(i + 1, v.toString());
            }
            ps.executeUpdate();
        }
    }
}
