package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.core.Question;
import edu.core.Quiz;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side quiz sessions. The generated quiz (including the answer key)
 * is stored per user so scoring happens on the backend - clients can no
 * longer fabricate scores, and every answered question feeds the mistake
 * intelligence pipeline via {@link Responses}.
 */
public final class QuizSessions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SESSION_TTL_DAYS = 7;

    private QuizSessions() {
    }

    /** Persists a generated quiz and returns its session id. */
    public static String create(Quiz quiz, String topic, String subject, String level,
                                String difficulty, String mode, AuthService.AuthUser user) {
        String id = UUID.randomUUID().toString().substring(0, 10);
        ObjectNode s = MAPPER.createObjectNode();
        s.put("id", id);
        s.put("owner", user.id());
        s.put("title", quiz.title());
        s.put("subject", quiz.subject());
        s.put("topic", topic);
        s.put("level", level);
        s.put("difficulty", difficulty);
        s.put("mode", mode);
        s.put("created", Instant.now().toString());

        ArrayNode questions = s.putArray("questions");
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
            qn.put("correct_answer", q.correctAnswer());
            qn.put("explanation", q.explanation());
        }

        ObjectNode root = Store.obj(Store.read("quiz_sessions.json"));
        root.set(id, s);
        prune(root);
        Store.write("quiz_sessions.json", root);
        return id;
    }

    /**
     * Scores a session's answers server-side, records every response for
     * analytics, nudges revision items, and returns score + per-question review.
     */
    public static ObjectNode submit(String sessionId, JsonNode answers, AuthService.AuthUser user) {
        JsonNode session = Store.read("quiz_sessions.json").path(sessionId);
        if (session.isMissingNode()) {
            throw new IllegalArgumentException("quiz session not found or expired");
        }
        String owner = session.path("owner").asText("");
        if (!owner.isEmpty() && !owner.equals(user.id()) && !"admin".equals(user.role())) {
            throw new IllegalArgumentException("access denied");
        }

        int got = 0, total = 0;
        ArrayNode review = MAPPER.createArrayNode();
        ArrayNode recorded = MAPPER.createArrayNode();

        for (JsonNode q : session.path("questions")) {
            int num = q.path("number").asInt();
            int marks = q.path("marks").asInt(1);
            String correct = q.path("correct_answer").asText("");
            String type = q.path("type").asText("");
            String topic = q.path("topic").asText(session.path("topic").asText("General"));
            String given = answers.path(String.valueOf(num)).asText("").trim();

            total += marks;
            boolean ok = score(given, correct, type);
            int earned = ok ? marks : 0;
            got += earned;

            ObjectNode r = review.addObject();
            r.put("number", num);
            r.put("your_answer", given);
            r.put("correct_answer", correct);
            r.put("explanation", q.path("explanation").asText(""));
            r.put("marks", marks);
            r.put("earned", earned);
            r.put("correct", ok);

            // feed analytics pipeline
            Responses.recordScored(user, "quiz", sessionId, topic,
                    session.path("subject").asText("General"), type,
                    q.path("question").asText(""), given, correct,
                    marks, earned, ok);
            ObjectNode rr = recorded.addObject();
            rr.put("topic", topic);
            rr.put("subject", session.path("subject").asText("General"));
            rr.put("correct_flag", ok ? 1 : 0);
        }

        List<JsonNode> recList = new ArrayList<>();
        recorded.forEach(recList::add);
        RevisionService.onResponsesRecorded(user, recList);

        int pct = total == 0 ? 0 : (int) Math.round(100.0 * got / total);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("session_id", sessionId);
        out.put("score", pct);
        out.put("marks", got + "/" + total);
        out.set("review", review);
        return out;
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

    private static void prune(ObjectNode root) {
        Instant cutoff = Instant.now().minus(SESSION_TTL_DAYS, ChronoUnit.DAYS);
        List<String> stale = new ArrayList<>();
        root.fields().forEachRemaining(e -> {
            try {
                Instant created = Instant.parse(e.getValue().path("created").asText(""));
                if (created.isBefore(cutoff)) stale.add(e.getKey());
            } catch (Exception ignored) {
            }
        });
        stale.forEach(root::remove);
    }
}
