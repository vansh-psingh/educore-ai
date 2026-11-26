package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.core.AssessmentEngine;
import edu.core.Question;
import edu.core.QuestionType;
import edu.core.Quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hybrid quiz/exam generator.
 *
 * Mode A ("notes"): when the student supplies source material, questions are
 * generated deterministically by edu.core.AssessmentEngine from that material.
 *
 * Mode B ("ai_knowledge"): when no source is supplied, the configured AI
 * provider generates original questions matched to the requested academic
 * level and difficulty. The response must be strict JSON; it is parsed and
 * validated server-side.
 *
 * If AI generation is unavailable or returns unusable output, the local
 * engine is used as a last resort ("fallback") so generation never hard-fails
 * just because notes were not uploaded.
 */
public final class HybridAssessment {

    public static final String MODE_NOTES = "notes";
    public static final String MODE_AI = "ai_knowledge";
    public static final String MODE_FALLBACK = "fallback";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AssessmentEngine ENGINE = new AssessmentEngine();

    public record Result(Quiz quiz, String mode) {
    }

    private HybridAssessment() {
    }

    public static Result generate(String subject, String topic, String level,
                                  String difficulty, int count, String source) {
        return generateForUser("", subject, topic, level, difficulty, count, source);
    }

    public static Result generateForUser(String userId, String subject, String topic, String level,
                                         String difficulty, int count, String source) {
        String src = source == null ? "" : source.trim();
        int n = Math.max(1, Math.min(30, count));

        if (!src.isEmpty()) {
            Quiz quiz = ENGINE.generate(subject, topic, level, difficulty, n,
                    Set.of(QuestionType.MCQ, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER), src);
            return new Result(quiz, MODE_NOTES);
        }

        List<Question> aiQuestions = aiGenerate(userId, subject, topic, level, difficulty, n);
        if (!aiQuestions.isEmpty()) {
            Quiz quiz = new Quiz(topic + " - Practice Quiz", subject,
                    difficulty == null || difficulty.isBlank() ? "standard" : difficulty, aiQuestions);
            return new Result(quiz, MODE_AI);
        }

        // last resort: local engine without source material. The engine emits a
        // degenerate "no source material" question when given nothing - detect
        // that and fail honestly instead of serving a useless quiz.
        Quiz quiz = ENGINE.generate(subject, topic, level, difficulty, n,
                Set.of(QuestionType.MCQ, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER), "");
        if (isDegenerate(quiz)) {
            throw new IllegalArgumentException(
                    "AI question generation is temporarily unavailable. Please try again in a moment.");
        }
        return new Result(quiz, MODE_FALLBACK);
    }

    private static boolean isDegenerate(Quiz quiz) {
        return quiz.questions().size() <= 1
                && quiz.questions().get(0).text().toLowerCase().contains("no usable source");
    }

    /** Asks the AI provider for strict-JSON questions; returns empty list on any failure. */
    private static List<Question> aiGenerate(String userId, String subject, String topic, String level,
                                             String difficulty, int count) {
        try {
            String instructions =
                    "You are EDUCORE AI's assessment generator. Create original practice questions. "
                    + "Output ONLY a single JSON object - no markdown, no commentary, no code fences.\n"
                    + "Shape: {\"questions\":[{\"question\":\"...\",\"type\":\"mcq|true_false|short_answer\","
                    + "\"options\":[\"...\"],\"correct_answer\":\"...\",\"explanation\":\"...\",\"marks\":1}]}\n"
                    + "Rules:\n"
                    + "- mcq: exactly 4 options; correct_answer must exactly match one option.\n"
                    + "- true_false: options must be [\"True\",\"False\"]; correct_answer is True or False.\n"
                    + "- short_answer: options is []; correct_answer is a concise expected answer (keywords).\n"
                    + "- long_answer: options is []; correct_answer is a short model answer outline; "
                    + "marks should be 5-10. Use for 1-2 questions max when count >= 5.\n"
                    + "- Vary question types; do not repeat questions.\n"
                    + "- Every question needs a correct_answer and a short explanation.\n"
                    + "- Match the academic level and difficulty exactly. Never invent facts.\n"
                    + "- JSON escaping: write LaTeX backslashes doubled (\\\\frac, \\\\sqrt, \\\\( \\\\)). "
                    + "Never emit a single backslash before ( ) [ ] or other non-escape characters.";
            String input = "Subject: " + subject + "\nTopic: " + topic + "\n"
                    + "Academic level: " + level + "\nDifficulty: " + difficulty + "\n"
                    + "Number of questions: " + count + "\n"
                    + "Use LaTeX notation ($$...$$ for display math, \\(...\\) inline) for any formulas.";

            String raw = AIProvider.generateForUser(userId, instructions, input);
            List<Question> parsed = parseQuestions(raw, count);
            if (parsed.isEmpty()) {
                System.err.println("[HybridAssessment] AI returned unparseable output:\n" + raw);
            }
            return parsed;
        } catch (Exception e) {
            System.err.println("[HybridAssessment] AI generation failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Lenient JSON extraction: tolerates code fences and surrounding prose. */
    static List<Question> parseQuestions(String raw, int maxCount) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        try {
            JsonNode root = MAPPER.readTree(fixJsonEscapes(text.substring(start, end + 1)));
            JsonNode arr = root.path("questions");
            if (!arr.isArray()) arr = root; // allow a bare array too
            if (!arr.isArray()) return List.of();

            List<Question> out = new ArrayList<>();
            for (JsonNode q : arr) {
                if (out.size() >= maxCount) break;
                String qText = q.path("question").asText("").trim();
                if (qText.isEmpty()) continue;
                QuestionType type = mapType(q.path("type").asText("mcq"));
                List<String> options = new ArrayList<>();
                JsonNode opts = q.path("options");
                if (opts.isArray()) {
                    for (JsonNode o : opts) {
                        String s = o.asText("").trim();
                        if (!s.isEmpty()) options.add(s);
                    }
                }
                if (type == QuestionType.MCQ && options.size() < 2) continue;
                if (type == QuestionType.TRUE_FALSE && options.isEmpty()) {
                    options.add("True");
                    options.add("False");
                }
                String answer = q.path("correct_answer").asText("").trim();
                if (answer.isEmpty()) continue;
                String explanation = q.path("explanation").asText("").trim();
                int marks = Math.max(1, Math.min(10, q.path("marks").asInt(1)));
                out.add(new Question(qText, type, options, answer, explanation, marks,
                        q.path("topic").asText("")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Models sometimes emit LaTeX like \\(...\\) with a single backslash inside
     * JSON strings, which is an invalid JSON escape. Doubles any backslash that
     * is not part of a valid JSON escape sequence so the document still parses.
     */
    static String fixJsonEscapes(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') { inString = !inString; sb.append(c); continue; }
            if (inString && c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if ("\"\\/bfnrt".indexOf(n) >= 0 || n == 'u') {
                    // valid JSON escape - copy both chars verbatim
                    sb.append(c).append(n);
                    i++;
                    continue;
                }
                // stray backslash (e.g. LaTeX \( \frac): emit an escaped
                // backslash, then let the next char through normally
                sb.append("\\\\");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static QuestionType mapType(String t) {
        return switch (t.trim().toLowerCase()) {
            case "true_false", "true/false", "tf" -> QuestionType.TRUE_FALSE;
            case "short_answer", "short" -> QuestionType.SHORT_ANSWER;
            case "long_answer", "essay", "descriptive", "long" -> QuestionType.LONG_ANSWER;
            default -> QuestionType.MCQ;
        };
    }
}
