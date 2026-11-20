package edu.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A generated assessment. Holds the protected question bank (with answers)
 * and exposes two separated views:
 *   - studentView(): safe JSON with no answers (pre-submission)
 *   - answerKey(): protected JSON with answers + explanations (post-submission/grading)
 */
public class Quiz {
    private final String title;
    private final String subject;
    private final String difficulty;
    private final List<Question> questions;   // protected - grading data lives here

    public Quiz(String title, String subject, String difficulty, List<Question> questions) {
        this.title = title;
        this.subject = subject;
        this.difficulty = difficulty;
        this.questions = List.copyOf(questions);
    }

    public String title() { return title; }
    public String subject() { return subject; }
    public String difficulty() { return difficulty; }
    public List<Question> questions() { return questions; }
    public int totalMarks() { return questions.stream().mapToInt(Question::marks).sum(); }

    /** JSON safe to send to students BEFORE submission - contains no answers. */
    public String studentViewJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"title\": \"").append(escape(title)).append("\",\n");
        sb.append("  \"subject\": \"").append(escape(subject)).append("\",\n");
        sb.append("  \"difficulty\": \"").append(escape(difficulty)).append("\",\n");
        sb.append("  \"total_marks\": ").append(totalMarks()).append(",\n");
        sb.append("  \"questions\": [\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            sb.append("    {\n");
            sb.append("      \"number\": ").append(i + 1).append(",\n");
            sb.append("      \"question\": \"").append(escape(q.text())).append("\",\n");
            sb.append("      \"type\": \"").append(q.type().name().toLowerCase()).append("\",\n");
            sb.append("      \"options\": ").append(optionsJson(q)).append(",\n");
            sb.append("      \"marks\": ").append(q.marks()).append(",\n");
            sb.append("      \"topic\": \"").append(escape(q.topic())).append("\"\n");
            sb.append("    }").append(i < questions.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    /** Protected answer key - only for post-submission review or grading. */
    public String answerKeyJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"title\": \"").append(escape(title)).append("\",\n");
        sb.append("  \"questions\": [\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            sb.append("    {\n");
            sb.append("      \"number\": ").append(i + 1).append(",\n");
            sb.append("      \"correct_answer\": \"").append(escape(q.correctAnswer())).append("\",\n");
            sb.append("      \"explanation\": \"").append(escape(q.explanation())).append("\",\n");
            sb.append("      \"marks\": ").append(q.marks()).append("\n");
            sb.append("    }").append(i < questions.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private String optionsJson(Question q) {
        if (q.options().isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < q.options().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escape(q.options().get(i))).append("\"");
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
