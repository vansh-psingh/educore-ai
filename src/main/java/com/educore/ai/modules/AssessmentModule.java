package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;
import edu.core.AssessmentEngine;
import edu.core.QuestionType;
import edu.core.Quiz;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assessment module - generates practice quizzes deterministically
 * from supplied source material using edu.core.AssessmentEngine.
 *
 * Input params (key: value):
 *   subject, topic, level, difficulty (easy|standard|hard), count, source
 * Add "answers" or "answer key" to include the protected key.
 *
 * Generated questions are practice material only - never official exams.
 */
public final class AssessmentModule implements EducoreModule {

    private static final Pattern PARAM = Pattern.compile("([a-zA-Z_]+)\\s*[:=]\\s*([^;\\n]+?)(?=\\s+[a-zA-Z_]+\\s*[:=]|$)");

    private final AssessmentEngine engine = new AssessmentEngine();

    @Override
    public String respond(String input) {
        Map<String, String> p = parseParams(input);
        String lower = input.toLowerCase(Locale.ROOT);

        String topic = param(p, "topic", "subject");
        if (topic.isEmpty()) {
            topic = guessTopic(lower);
        }
        if (topic.isEmpty()) {
            return "Tell me what to quiz you on, e.g.\n"
                    + "  \"practice quiz topic: fractions, source: Fractions: part of a whole\"\n"
                    + "Providing source material (Term: definition lines) produces better questions.";
        }

        String subject = param(p, "subject");
        if (subject.isEmpty()) subject = "General";
        String level = param(p, "level", "academic_level");
        if (level.isEmpty()) level = "General";
        String difficulty = param(p, "difficulty");
        if (difficulty.isEmpty()) difficulty = "standard";
        int count = parseCount(param(p, "count", "questions", "n"));
        String source = param(p, "source", "material", "text");

        Quiz quiz = engine.generate(subject, topic, level, difficulty, count,
                Set.of(QuestionType.MCQ, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER), source);

        boolean showKey = lower.contains("answer") || lower.contains("key") || lower.contains("solution");

        StringBuilder sb = new StringBuilder();
        sb.append(AssessmentEngine.DISCLAIMER).append("\n\n");
        sb.append(quiz.studentViewJson());
        if (showKey) {
            sb.append("\n--- ANSWER KEY (review after attempting) ---\n");
            sb.append(quiz.answerKeyJson());
        } else {
            sb.append("\n(Try the quiz first. Add \"answers\" to see the answer key.)");
        }
        return sb.toString();
    }

    private Map<String, String> parseParams(String input) {
        Map<String, String> map = new HashMap<>();
        Matcher m = PARAM.matcher(input);
        while (m.find()) {
            map.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim().replaceAll("[,;\\s]+$", ""));
        }
        return map;
    }

    private String param(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private int parseCount(String s) {
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private String guessTopic(String lower) {
        // reuse knowledge topics for a sensible default topic name
        for (JsonNode t : JsonStore.load("data/knowledge.json").path("topics")) {
            for (JsonNode kw : t.path("keywords")) {
                if (lower.contains(kw.asText().toLowerCase(Locale.ROOT))
                        || lower.contains(t.path("title").asText().toLowerCase(Locale.ROOT))) {
                    return t.path("title").asText();
                }
            }
        }
        return "";
    }
}
