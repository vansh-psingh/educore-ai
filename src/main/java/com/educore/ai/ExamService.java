package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.core.AssessmentEngine;
import edu.core.Question;
import edu.core.Quiz;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exams & Past Papers backend.
 * Exams are generated deterministically by edu.core.AssessmentEngine from
 * supplied source material (practice questions - NOT official exam papers).
 * Exams and attempt history persist in data/store/*.json.
 */
public final class ExamService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExamService() {
    }

    // ---------- generate ----------

    public static ObjectNode generate(JsonNode req, AuthService.AuthUser user) {
        String topic = req.path("topic").asText("General").trim();
        if (topic.isEmpty()) throw new IllegalArgumentException("topic is required");

        int count = Math.max(1, Math.min(30, req.path("count").asInt(5)));
        int duration = Math.max(1, Math.min(180, req.path("duration_min").asInt(15)));

        HybridAssessment.Result result = HybridAssessment.generateForUser(
                user.id(),
                req.path("subject").asText("General"),
                topic,
                req.path("level").asText("General"),
                req.path("difficulty").asText("standard"),
                count,
                req.path("source").asText(""));
        Quiz quiz = result.quiz();

        String id = UUID.randomUUID().toString().substring(0, 8);
        ObjectNode exam = MAPPER.createObjectNode();
        exam.put("id", id);
        exam.put("title", quiz.title());
        exam.put("subject", quiz.subject());
        exam.put("topic", topic);
        exam.put("level", req.path("level").asText("General"));
        exam.put("difficulty", quiz.difficulty());
        exam.put("generation_mode", result.mode());
        exam.put("generated_from", HybridAssessment.MODE_NOTES.equals(result.mode()) ? "notes" : "ai_knowledge");
        exam.put("duration_min", duration);
        exam.put("created", java.time.Instant.now().toString());
        exam.put("owner", user.id());
        exam.put("disclaimer", AssessmentEngine.DISCLAIMER);

        // student-safe questions (no answers)
        ArrayNode questions = exam.putArray("questions");
        int n = 0;
        for (Question q : quiz.questions()) {
            ObjectNode qn = questions.addObject();
            qn.put("number", ++n);
            qn.put("question", q.text());
            qn.put("type", q.type().name().toLowerCase());
            qn.put("marks", q.marks());
            qn.put("topic", q.topic());
            ArrayNode opts = qn.putArray("options");
            q.options().forEach(opts::add);
        }

        // protected key stored server-side only
        ArrayNode key = exam.putArray("key");
        n = 0;
        for (Question q : quiz.questions()) {
            ObjectNode k = key.addObject();
            k.put("number", ++n);
            k.put("correct_answer", q.correctAnswer());
            k.put("explanation", q.explanation());
            k.put("marks", q.marks());
        }

        ObjectNode exams = Store.obj(Store.read("exams.json"));
        exams.set(id, exam);
        Store.write("exams.json", exams);

        // return student view + meta (no key)
        ObjectNode out = MAPPER.createObjectNode();
        out.put("id", id);
        out.put("title", exam.path("title").asText());
        out.put("subject", exam.path("subject").asText());
        out.put("difficulty", exam.path("difficulty").asText());
        out.put("duration_min", duration);
        out.put("total_marks", quiz.totalMarks());
        out.set("questions", questions);
        out.put("generation_mode", result.mode());
        out.put("generated_from", HybridAssessment.MODE_NOTES.equals(result.mode()) ? "notes" : "ai_knowledge");
        out.put("disclaimer", AssessmentEngine.DISCLAIMER);
        return out;
    }

    // ---------- submit ----------

    public static ObjectNode submit(String id, JsonNode answers, AuthService.AuthUser user) {
        ObjectNode examsRoot = Store.obj(Store.read("exams.json"));
        JsonNode examNode = examsRoot.path(id);
        if (examNode.isMissingNode() || !(examNode instanceof ObjectNode)) {
            throw new IllegalArgumentException("exam not found: " + id);
        }
        ObjectNode exam = (ObjectNode) examNode;
        String owner = exam.path("owner").asText("");
        if (!owner.isEmpty() && !owner.equals(user.id()) && !"admin".equals(user.role())) {
            throw new IllegalArgumentException("access denied");
        }
        if (exam.path("submitted").asBoolean(false)) {
            JsonNode cached = exam.path("result");
            if (cached.isObject()) return (ObjectNode) cached.deepCopy();
            throw new IllegalArgumentException("exam already submitted");
        }
        JsonNode key = exam.path("key");
        Map<Integer, JsonNode> keyMap = new HashMap<>();
        for (JsonNode k : key) {
            keyMap.put(k.path("number").asInt(), k);
        }

        int got = 0, total = 0;
        ArrayNode review = MAPPER.createArrayNode();
        java.util.List<JsonNode> recorded = new java.util.ArrayList<>();
        java.util.List<ObjectNode> drafts = new java.util.ArrayList<>();
        String examSubject = exam.path("subject").asText("General");
        for (JsonNode k : key) {
            int num = k.path("number").asInt();
            int marks = k.path("marks").asInt(1);
            total += marks;
            String correct = k.path("correct_answer").asText("");
            String given = answers.path(String.valueOf(num)).asText("").trim();
            String qType = findType(exam, num);
            String topic = findTopic(exam, num);
            String qText = findQuestionText(exam, num);

            int earned = 0;
            boolean ok = false;
            JsonNode essayEval = null;
            String essayId = null;
            if ("long_answer".equals(qType)) {
                // dedicated rubric evaluation - stored as an essay submission,
                // always flagged as an AI suggestion pending teacher review
                try {
                    ObjectNode ereq = MAPPER.createObjectNode();
                    ereq.put("question", qText);
                    ereq.put("answer", given);
                    ereq.put("subject", exam.path("subject").asText("General"));
                    ereq.put("level", exam.path("level").asText("General"));
                    ereq.put("max_marks", marks);
                    ereq.put("exam_id", id);
                    ObjectNode sub = EssayService.evaluate(ereq, user);
                    essayId = sub.path("id").asText();
                    essayEval = sub.path("evaluation");
                    earned = (int) Math.round(essayEval.path("total_score").asDouble());
                    earned = Math.max(0, Math.min(marks, earned));
                    ok = earned >= marks;
                } catch (Exception e) {
                    earned = 0; // AI unavailable - recorded as pending, no marks invented
                }
            } else {
                ok = score(given, correct, qType);
                earned = ok ? marks : 0;
            }
            got += earned;

            ObjectNode r = review.addObject();
            r.put("number", num);
            r.put("your_answer", given);
            r.put("correct_answer", correct);
            r.put("explanation", k.path("explanation").asText(""));
            r.put("marks", marks);
            r.put("earned", earned);
            r.put("correct", ok);
            if ("long_answer".equals(qType)) {
                r.put("ai_suggestion", true);
                if (essayId != null) r.put("essay_id", essayId);
                if (essayEval != null) r.set("evaluation", essayEval);
                r.put("note", EssayService.DISCLAIMER);
            }

            // per-question analytics feed (batched - one store write for all questions)
            drafts.add(Responses.draftScored(user, "exam", id, topic, examSubject, qType,
                    qText, given, correct, marks, earned, ok));
            ObjectNode rec = MAPPER.createObjectNode();
            rec.put("topic", topic);
            rec.put("subject", examSubject);
            rec.put("correct_flag", ok ? 1 : 0);
            recorded.add(rec);
        }

        Responses.flush(drafts);
        RevisionService.onResponsesRecorded(user, recorded);

        int pct = total == 0 ? 0 : (int) Math.round(100.0 * got / total);

        // persist attempt
        ObjectNode hist = Store.obj(Store.read("exam_history.json"));
        ArrayNode attempts;
        if (hist.path("attempts").isArray()) {
            attempts = (ArrayNode) hist.path("attempts");
        } else {
            attempts = hist.putArray("attempts");
        }
        ObjectNode att = attempts.addObject();
        att.put("id", java.util.UUID.randomUUID().toString().substring(0, 8));
        att.put("exam_id", id);
        att.put("title", exam.path("title").asText());
        att.put("subject", exam.path("subject").asText());
        att.put("score", pct);
        att.put("marks", got + "/" + total);
        att.put("date", java.time.Instant.now().toString());
        att.put("owner", exam.path("owner").asText(""));
        att.set("review", review); // per-question detail for insights
        Store.write("exam_history.json", hist);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("exam_id", id);
        out.put("score", pct);
        out.put("marks", got + "/" + total);
        out.set("review", review);

        // mark submitted + cache result so resubmits are idempotent
        exam.put("submitted", true);
        exam.set("result", out.deepCopy());
        Store.write("exams.json", examsRoot);
        return out;
    }

    public static ArrayNode history(AuthService.AuthUser user) {
        JsonNode hist = Store.read("exam_history.json");
        JsonNode attempts = hist.path("attempts");
        ArrayNode all = attempts.isArray() ? (ArrayNode) attempts : MAPPER.createArrayNode();
        ArrayNode mine = MAPPER.createArrayNode();
        boolean admin = "admin".equals(user.role());
        for (JsonNode a : all) {
            String owner = a.path("owner").asText("");
            if (admin || owner.isEmpty() || owner.equals(user.id())) {
                mine.add(a);
            }
        }
        return mine;
    }

    private static String findType(JsonNode exam, int num) {
        for (JsonNode q : exam.path("questions")) {
            if (q.path("number").asInt() == num) {
                return q.path("type").asText();
            }
        }
        return "";
    }

    private static String findQuestionText(JsonNode exam, int num) {
        for (JsonNode q : exam.path("questions")) {
            if (q.path("number").asInt() == num) {
                return q.path("question").asText("");
            }
        }
        return "";
    }

    private static String findTopic(JsonNode exam, int num) {
        for (JsonNode q : exam.path("questions")) {
            if (q.path("number").asInt() == num) {
                String t = q.path("topic").asText("");
                return t.isEmpty() ? "General" : t;
            }
        }
        return "General";
    }

    private static boolean score(String given, String correct, String type) {
        String g = given.toLowerCase().trim();
        String c = correct.toLowerCase().trim();
        if (g.equals(c)) return true;
        if ("short_answer".equals(type) && !g.isEmpty()) {
            Set<String> kw = new HashSet<>(java.util.Arrays.asList(c.split("[^a-z0-9]+")));
            kw.removeIf(w -> w.length() < 4);
            if (kw.isEmpty()) return false;
            long hits = kw.stream().filter(g::contains).count();
            return hits >= Math.max(1, (int) Math.ceil(kw.size() * 0.5));
        }
        return false;
    }
}
