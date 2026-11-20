package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Random;

/**
 * Module 2/6 - Study planning and practice generation.
 * Produces study plans and quizzes from data/question_bank.json.
 */
public final class StudyModule implements EducoreModule {

    private final JsonNode questionBank;
    private final Random random = new Random();

    public StudyModule() {
        this.questionBank = JsonStore.load("data/question_bank.json");
    }

    public String respond(String input) {
        String lower = input.toLowerCase(Locale.ROOT);

        if (lower.contains("plan") || lower.contains("schedule") || lower.contains("routine")) {
            return buildStudyPlan(lower);
        }
        if (lower.contains("quiz") || lower.contains("practice") || lower.contains("test me")) {
            return buildQuiz(lower);
        }
        return "I can help with study plans and practice quizzes. "
                + "Try \"give me a study plan for math\" or \"quiz me on biology\".";
    }

    private String buildStudyPlan(String lower) {
        String subject = detectSubject(lower);
        StringBuilder sb = new StringBuilder("Study plan for " + subject + ":\n");
        sb.append("1. Review core concepts (30 min)\n");
        sb.append("2. Work through 5 practice problems\n");
        sb.append("3. Summarize what you learned in your own words\n");
        sb.append("4. Take a short quiz to check recall\n");
        sb.append("5. Schedule a review of weak areas tomorrow\n\n");
        sb.append("Consistency beats intensity - 40 focused minutes daily "
                + "is better than one long session.");
        return sb.toString();
    }

    private String buildQuiz(String lower) {
        String subject = detectSubject(lower);
        JsonNode questions = questionBank.path("questions");
        int count = 0;
        StringBuilder sb = new StringBuilder("Quick quiz (" + subject + "):\n");

        for (JsonNode q : questions) {
            if (!q.path("subject").asText().equalsIgnoreCase(subject)) {
                continue;
            }
            sb.append("\nQ: ").append(q.path("question").asText()).append('\n');
            sb.append("A: ").append(q.path("answer").asText()).append('\n');
            if (++count >= 3) {
                break;
            }
        }
        if (count == 0) {
            return "I don't have quiz questions for \"" + subject
                    + "\" yet. Available subjects: see /modules or try math, science, or history.";
        }
        return sb.toString();
    }

    private String detectSubject(String lower) {
        for (String s : new String[]{"math", "science", "biology", "history", "english", "physics"}) {
            if (lower.contains(s)) {
                return s;
            }
        }
        return "general";
    }
}
