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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Long-answer / essay evaluation engine.
 *
 * Evaluates descriptive answers against a marking rubric using the configured
 * AI provider. Results are ALWAYS labelled as AI-generated suggestions -
 * official marks require teacher review (see {@link #finalize}).
 *
 * Persistence: essay_submissions table (PostgreSQL) or
 * data/store/essay_submissions.json in legacy JSON mode.
 */
public final class EssayService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_Q_CHARS = 4000;
    private static final int MAX_A_CHARS = 20_000;

    public static final String DISCLAIMER =
            "AI-generated evaluation - a suggestion only. A teacher or examiner "
                    + "must review before official marks are awarded.";

    private EssayService() {
    }

    // ================= evaluate =================

    /**
     * Evaluates one long answer. Returns the stored submission node including
     * the AI evaluation. Throws IllegalArgumentException on bad input and
     * OpenAIException-style errors propagate as Exception.
     */
    public static ObjectNode evaluate(JsonNode req, AuthService.AuthUser user) throws Exception {
        String question = req.path("question").asText("").trim();
        String answer = req.path("answer").asText("").trim();
        if (question.isEmpty()) throw new IllegalArgumentException("question is required");
        if (answer.isEmpty()) throw new IllegalArgumentException("answer is required");
        if (answer.length() > MAX_A_CHARS) answer = answer.substring(0, MAX_A_CHARS);

        double maxMarks = req.path("max_marks").asDouble(10);
        if (maxMarks <= 0) maxMarks = 10;
        if (maxMarks > 100) maxMarks = 100;

        String subject = req.path("subject").asText("General");
        String level = req.path("level").asText("General");
        String rubricJson = req.path("rubric").isObject() || req.path("rubric").isTextual()
                ? req.path("rubric").toString() : "";

        ObjectNode evaluation = aiEvaluate(user.id(), question, answer, subject, level,
                maxMarks, rubricJson, req.path("context").asText(""));

        String id = UUID.randomUUID().toString().substring(0, 8);
        String now = java.time.Instant.now().toString();
        ObjectNode sub = MAPPER.createObjectNode();
        sub.put("id", id);
        sub.put("user", user.id());
        sub.put("exam_id", req.path("exam_id").asText(""));
        sub.put("question", question.length() > MAX_Q_CHARS ? question.substring(0, MAX_Q_CHARS) : question);
        sub.put("answer", answer);
        sub.put("subject", subject);
        sub.put("level", level);
        sub.put("max_marks", maxMarks);
        if (!rubricJson.isEmpty()) {
            try { sub.set("rubric", MAPPER.readTree(rubricJson)); } catch (Exception ignored) {}
        }
        sub.set("evaluation", evaluation);
        sub.put("status", "ai_evaluated");
        sub.putNull("final_marks");
        sub.put("final_feedback", "");
        sub.put("reviewed_by", "");
        sub.put("created", now);
        sub.put("updated", now);
        sub.put("disclaimer", DISCLAIMER);

        save(sub);
        AuditService.log(user.id(), user.email(), "essay_evaluated", id);
        return sub;
    }

    /** Core AI call - shared by the endpoint and exam submission flow. */
    public static ObjectNode aiEvaluate(String userId, String question, String answer,
                                        String subject, String level, double maxMarks,
                                        String rubricJson, String context) throws Exception {
        String instructions = """
                You are an expert academic examiner. Evaluate the student's long answer
                against a marking rubric. Output ONLY a single JSON object - no markdown,
                no commentary, no code fences.
                Shape:
                {"criteria":[{"name":"...","score":0,"max":0,"comment":"..."}],
                 "total_score":0,"max_marks":%s,"feedback":"...",
                 "strengths":["..."],"improvements":["..."],"missing_concepts":["..."]}
                Rules:
                - Define 3-6 criteria relevant to THIS question (e.g. relevance,
                  conceptual understanding, completeness, accuracy, explanation
                  quality, organization) unless a rubric is supplied - then use it.
                - Each criterion score must be between 0 and its max; total_score
                  must equal the sum and never exceed max_marks.
                - Give partial marks fairly. feedback must be constructive.
                - missing_concepts lists key ideas absent from the answer.
                - Match the academic level: do not reward beyond-level vocabulary
                  or penalize simple correct answers at beginner level.
                """.formatted((int) maxMarks);

        StringBuilder input = new StringBuilder();
        input.append("Subject: ").append(subject).append('\n');
        input.append("Academic level: ").append(level).append('\n');
        input.append("Max marks: ").append(maxMarks).append('\n');
        if (context != null && !context.isBlank()) {
            input.append("Context/source material:\n").append(context, 0, Math.min(context.length(), 8000)).append('\n');
        }
        if (rubricJson != null && !rubricJson.isBlank()) {
            input.append("Supplied rubric (use exactly these criteria):\n").append(rubricJson).append('\n');
        }
        input.append("Question:\n").append(question).append("\n\nStudent answer:\n").append(answer);

        String raw = AIProvider.generateForUser(userId, instructions, input.toString());
        return parseEvaluation(raw, maxMarks);
    }

    /** Lenient parse of the evaluation JSON; clamps scores to valid ranges. */
    static ObjectNode parseEvaluation(String raw, double maxMarks) {
        ObjectNode out = MAPPER.createObjectNode();
        out.putArray("criteria");
        out.put("total_score", 0);
        out.put("max_marks", maxMarks);
        out.put("feedback", "");
        out.putArray("strengths");
        out.putArray("improvements");
        out.putArray("missing_concepts");
        if (raw == null || raw.isBlank()) return out;

        String text = raw.trim();
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return out;
        try {
            JsonNode root = MAPPER.readTree(HybridAssessment.fixJsonEscapes(text.substring(s, e + 1)));
            JsonNode crit = root.path("criteria");
            if (crit.isArray()) {
                ArrayNode dst = (ArrayNode) out.path("criteria");
                double total = 0;
                for (JsonNode c : crit) {
                    ObjectNode cn = dst.addObject();
                    cn.put("name", c.path("name").asText("Criterion"));
                    double mx = Math.max(0.5, c.path("max").asDouble(5));
                    double sc = Math.min(mx, Math.max(0, c.path("score").asDouble(0)));
                    cn.put("max", mx);
                    cn.put("score", sc);
                    cn.put("comment", c.path("comment").asText(""));
                    total += sc;
                }
                out.put("total_score", Math.min(maxMarks, Math.round(total * 100.0) / 100.0));
            } else {
                double ts = Math.min(maxMarks, Math.max(0, root.path("total_score").asDouble(0)));
                out.put("total_score", ts);
            }
            out.put("feedback", root.path("feedback").asText(""));
            copyStrings(root.path("strengths"), (ArrayNode) out.path("strengths"));
            copyStrings(root.path("improvements"), (ArrayNode) out.path("improvements"));
            copyStrings(root.path("missing_concepts"), (ArrayNode) out.path("missing_concepts"));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void copyStrings(JsonNode src, ArrayNode dst) {
        if (!src.isArray()) return;
        for (JsonNode v : src) {
            String s = v.asText("").trim();
            if (!s.isEmpty()) dst.add(s);
        }
    }

    // ================= queries =================

    public static ArrayNode list(AuthService.AuthUser user, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        if (Db.configured()) {
            String sql = """
                    SELECT id, question, subject, level, max_marks, status,
                           final_marks, created_at, updated_at
                    FROM essay_submissions
                    WHERE user_id = ? OR ? = 'teacher' OR ? = 'admin'
                    ORDER BY seq DESC LIMIT ?
                    """;
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, user.id());
                ps.setString(2, user.role());
                ps.setString(3, user.role());
                ps.setInt(4, Math.min(200, Math.max(1, limit)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(summaryNode(rs));
                }
            } catch (Exception e) {
                System.err.println("[essay] list failed: " + e.getMessage());
            }
            return out;
        }
        // JSON fallback
        JsonNode items = Store.read("essay_submissions.json").path("items");
        boolean staff = "teacher".equals(user.role()) || "admin".equals(user.role());
        List<JsonNode> mine = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode n : items) {
                if (staff || n.path("user").asText().equals(user.id())) mine.add(n);
            }
        }
        for (int i = mine.size() - 1; i >= 0 && out.size() < limit; i--) {
            JsonNode n = mine.get(i);
            ObjectNode m = out.addObject();
            m.put("id", n.path("id").asText());
            m.put("question", trunc(n.path("question").asText(), 140));
            m.put("subject", n.path("subject").asText());
            m.put("level", n.path("level").asText());
            m.put("max_marks", n.path("max_marks").asDouble());
            m.put("status", n.path("status").asText());
            m.put("final_marks", n.path("final_marks").isNumber() ? n.path("final_marks").asDouble() : -1);
            m.put("created", n.path("created").asText());
            m.put("updated", n.path("updated").asText());
        }
        return out;
    }

    public static ObjectNode get(String id, AuthService.AuthUser user) {
        ObjectNode sub = find(id);
        if (sub == null) throw new IllegalArgumentException("submission not found");
        boolean staff = "teacher".equals(user.role()) || "admin".equals(user.role());
        if (!staff && !sub.path("user").asText().equals(user.id())) {
            throw new IllegalArgumentException("access denied");
        }
        return sub;
    }

    /** Teacher/admin override of the AI-suggested marks. */
    public static ObjectNode finalize(String id, JsonNode req, AuthService.AuthUser reviewer) {
        if (!"teacher".equals(reviewer.role()) && !"admin".equals(reviewer.role())) {
            throw new IllegalArgumentException("only teachers or admins can finalize marks");
        }
        ObjectNode sub = find(id);
        if (sub == null) throw new IllegalArgumentException("submission not found");
        double max = sub.path("max_marks").asDouble(10);
        double marks = req.path("final_marks").asDouble(-1);
        if (marks < 0 || marks > max) {
            throw new IllegalArgumentException("final_marks must be between 0 and " + max);
        }
        String feedback = req.path("final_feedback").asText("");
        String now = java.time.Instant.now().toString();
        sub.put("final_marks", marks);
        sub.put("final_feedback", feedback);
        sub.put("status", "teacher_reviewed");
        sub.put("reviewed_by", reviewer.id());
        sub.put("updated", now);
        save(sub);
        AuditService.log(reviewer.id(), reviewer.email(), "essay_finalized", id,
                MAPPER.createObjectNode().put("final_marks", marks));
        return sub;
    }

    // ================= persistence =================

    private static ObjectNode summaryNode(ResultSet rs) throws Exception {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("id", rs.getString("id"));
        String q = rs.getString("question");
        m.put("question", q.length() > 140 ? q.substring(0, 140) + "..." : q);
        m.put("subject", rs.getString("subject"));
        m.put("level", rs.getString("level"));
        m.put("max_marks", rs.getDouble("max_marks"));
        m.put("status", rs.getString("status"));
        double fm = rs.getDouble("final_marks");
        m.put("final_marks", rs.wasNull() ? -1 : fm);
        try { m.put("created", rs.getTimestamp("created_at").toInstant().toString()); }
        catch (Exception e) { m.put("created", ""); }
        try { m.put("updated", rs.getTimestamp("updated_at").toInstant().toString()); }
        catch (Exception e) { m.put("updated", ""); }
        return m;
    }

    private static ObjectNode find(String id) {
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT id, user_id, exam_id, question, answer, subject, level, "
                                 + "max_marks, rubric, evaluation, status, final_marks, "
                                 + "final_feedback, reviewed_by, created_at, updated_at "
                                 + "FROM essay_submissions WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return rowToNode(rs);
                }
            } catch (Exception e) {
                System.err.println("[essay] find failed: " + e.getMessage());
                return null;
            }
        }
        for (JsonNode n : Store.read("essay_submissions.json").path("items")) {
            if (id.equals(n.path("id").asText())) return (ObjectNode) n.deepCopy();
        }
        return null;
    }

    private static ObjectNode rowToNode(ResultSet rs) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", rs.getString("id"));
        n.put("user", rs.getString("user_id"));
        n.put("exam_id", rs.getString("exam_id"));
        n.put("question", rs.getString("question"));
        n.put("answer", rs.getString("answer"));
        n.put("subject", rs.getString("subject"));
        n.put("level", rs.getString("level"));
        n.put("max_marks", rs.getDouble("max_marks"));
        n.set("rubric", readJsonCol(rs, "rubric"));
        n.set("evaluation", readJsonCol(rs, "evaluation"));
        n.put("status", rs.getString("status"));
        double fm = rs.getDouble("final_marks");
        if (rs.wasNull()) n.putNull("final_marks"); else n.put("final_marks", fm);
        n.put("final_feedback", rs.getString("final_feedback"));
        n.put("reviewed_by", rs.getString("reviewed_by"));
        n.put("created", rs.getTimestamp("created_at").toInstant().toString());
        n.put("updated", rs.getTimestamp("updated_at").toInstant().toString());
        n.put("disclaimer", DISCLAIMER);
        return n;
    }

    private static JsonNode readJsonCol(ResultSet rs, String col) throws Exception {
        String s = rs.getString(col);
        return s == null ? MAPPER.nullNode() : MAPPER.readTree(s);
    }

    private static void save(ObjectNode sub) {
        if (Db.configured()) {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement("""
                         INSERT INTO essay_submissions
                           (id, user_id, exam_id, question, answer, subject, level,
                            max_marks, rubric, evaluation, status, final_marks,
                            final_feedback, reviewed_by, created_at, updated_at)
                         VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)
                         ON CONFLICT (id) DO UPDATE SET
                           evaluation = EXCLUDED.evaluation,
                           status = EXCLUDED.status,
                           final_marks = EXCLUDED.final_marks,
                           final_feedback = EXCLUDED.final_feedback,
                           reviewed_by = EXCLUDED.reviewed_by,
                           updated_at = EXCLUDED.updated_at
                         """)) {
                int i = 1;
                ps.setString(i++, sub.path("id").asText());
                ps.setString(i++, sub.path("user").asText());
                ps.setString(i++, sub.path("exam_id").asText(""));
                ps.setString(i++, sub.path("question").asText());
                ps.setString(i++, sub.path("answer").asText());
                ps.setString(i++, sub.path("subject").asText());
                ps.setString(i++, sub.path("level").asText());
                ps.setDouble(i++, sub.path("max_marks").asDouble(10));
                ps.setString(i++, sub.has("rubric") && sub.path("rubric").isObject()
                        ? sub.path("rubric").toString() : null);
                ps.setString(i++, sub.path("evaluation").toString());
                ps.setString(i++, sub.path("status").asText("ai_evaluated"));
                if (sub.path("final_marks").isNumber()) ps.setDouble(i++, sub.path("final_marks").asDouble());
                else ps.setNull(i++, java.sql.Types.DOUBLE);
                ps.setString(i++, sub.path("final_feedback").asText(""));
                ps.setString(i++, sub.path("reviewed_by").asText(""));
                ps.setString(i++, sub.path("created").asText());
                ps.setString(i, sub.path("updated").asText());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("essay save failed: " + e.getMessage(), e);
            }
            return;
        }
        // JSON mode
        ObjectNode root = Store.obj(Store.read("essay_submissions.json"));
        ArrayNode items = root.path("items").isArray()
                ? (ArrayNode) root.path("items") : root.putArray("items");
        // replace existing or append
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path("id").asText().equals(sub.path("id").asText())) {
                items.set(i, sub);
                Store.write("essay_submissions.json", root);
                return;
            }
        }
        items.add(sub);
        Store.write("essay_submissions.json", root);
    }

    private static String trunc(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
